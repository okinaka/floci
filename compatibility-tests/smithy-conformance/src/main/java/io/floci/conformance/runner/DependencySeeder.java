package io.floci.conformance.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pre-seeds resources that a case's input <em>references</em> but does not
 * create, so the operation under test isn't rejected for "the referenced
 * resource doesn't exist / isn't verified" instead of exercising its real
 * logic.
 *
 * <p>Some operations validate that a referenced resource already exists. SESv2
 * {@code CreateConfigurationSet}, for instance, rejects a
 * {@code TrackingOptions.CustomRedirectDomain} that isn't a verified email
 * identity with {@code BadRequestException: Domain <x> is not verified}. The
 * harness synthesizes a format-valid domain, but never verified it, so the
 * create fails and every dependent read/delete then 404s — a cascade rooted in
 * one missing dependency.
 *
 * <p>This seeder is the generic remedy: a small, declarative table of
 * {@link SeedRule}s says "a value carried by member {@code triggerMember} must
 * be seeded by calling {@code seedOperation} with the value at
 * {@code seedInputMember}". The runner walks each case's (already salted) input,
 * matches rules, and fires the seed operations through the same encoder /
 * invoker before sending the case — so the mechanism is protocol- and
 * service-agnostic while the dependency facts stay data, not code.
 */
public final class DependencySeeder {

    /**
     * One dependency fact.
     *
     * @param triggerMember   input member whose textual value names a resource
     *                        that must pre-exist (e.g. {@code CustomRedirectDomain}).
     *                        Matched anywhere in the input tree, at any nesting depth.
     * @param seedOperation   operation that creates that resource (e.g.
     *                        {@code CreateEmailIdentity}).
     * @param seedInputMember member of the seed operation's input that takes the
     *                        value (e.g. {@code EmailIdentity}).
     * @param template        fixed members the seed operation needs beyond the
     *                        name (e.g. a table's key schema), merged into the
     *                        seed input; {@code null} for name-only creates.
     * @param creators        operations other than {@code seedOperation} that
     *                        create the resource themselves (DynamoDB
     *                        {@code ImportTable} carries a {@code TableName}).
     * @param deleteOperation operation that removes a seeded resource, with
     *                        {@code deleteInputMember} taking the name. Names are
     *                        shared per case label across operations, so a
     *                        table seeded for {@code DescribeTable} would make the
     *                        same-label {@code CreateTable} collide; the runner
     *                        deletes a seeded resource before running a creator
     *                        case on it. {@code null} when no such clean-up exists.
     */
    public record SeedRule(String triggerMember, String seedOperation, String seedInputMember,
                           JsonNode template, java.util.Set<String> creators,
                           String deleteOperation, String deleteInputMember) {
        public SeedRule(String triggerMember, String seedOperation, String seedInputMember) {
            this(triggerMember, seedOperation, seedInputMember, null, java.util.Set.of(), null, null);
        }
    }

    /** A concrete dependency discovered in a case input: seed {@code value} via {@code operation}. */
    public record Seed(String operation, String inputMember, String value, JsonNode template,
                       java.util.Set<String> creators, String deleteOperation, String deleteInputMember) {
        public Seed(String operation, String inputMember, String value) {
            this(operation, inputMember, value, null, java.util.Set.of(), null, null);
        }

        /** True when {@code caseOperation} creates the resource itself, so seeding would collide. */
        public boolean createdBy(String caseOperation) {
            return operation.equals(caseOperation) || creators.contains(caseOperation);
        }
    }

    /** No rules — the common case for services with no cross-resource dependencies. */
    public static final DependencySeeder NONE = new DependencySeeder(List.of());

    private final List<SeedRule> rules;

    public DependencySeeder(List<SeedRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * SESv2 rules:
     * <ul>
     *   <li>a {@code ConfigurationSetName} referenced by Send/Get/Delete/Put
     *       operations must name an existing configuration set, so seed it with
     *       {@code CreateConfigurationSet} (a minimal, name-only create);
     *   <li>a configuration set's custom redirect/tracking domain must be a
     *       verified email identity, so seed it with {@code CreateEmailIdentity}
     *       (which Floci auto-verifies) before the referencing operation runs;
     *   <li>a delivery option's {@code SendingPoolName} must name an existing
     *       dedicated IP pool, so seed it with {@code CreateDedicatedIpPool}.
     * </ul>
     */
    public static DependencySeeder sesV2() {
        return new DependencySeeder(List.of(
                new SeedRule("ConfigurationSetName", "CreateConfigurationSet", "ConfigurationSetName"),
                new SeedRule("CustomRedirectDomain", "CreateEmailIdentity", "EmailIdentity"),
                new SeedRule("SendingPoolName", "CreateDedicatedIpPool", "PoolName")));
    }

    /**
     * SESv1 rules:
     * <ul>
     *   <li>{@code SetIdentity*} attribute operations (and Send*) reject an
     *       {@code Identity} that isn't a verified email address or domain, so
     *       seed it with {@code VerifyEmailIdentity} (Floci marks it verified
     *       immediately);
     *   <li>the {@code *ConfigurationSetTrackingOptions} operations reject a
     *       {@code ConfigurationSetName} that doesn't exist, so seed it with
     *       {@code CreateConfigurationSet} — whose v1 input nests the name under
     *       {@code ConfigurationSet.Name};
     *   <li>tracking options reject a {@code CustomRedirectDomain} that isn't a
     *       verified domain, so seed it with {@code VerifyDomainIdentity}.
     * </ul>
     */
    public static DependencySeeder sesV1() {
        return new DependencySeeder(List.of(
                new SeedRule("Identity", "VerifyEmailIdentity", "EmailAddress"),
                new SeedRule("ConfigurationSetName", "CreateConfigurationSet", "ConfigurationSet.Name"),
                new SeedRule("CustomRedirectDomain", "VerifyDomainIdentity", "Domain")));
    }

    /**
     * DynamoDB rule: every table-scoped operation (Describe*, Update*, item
     * reads and writes) answers {@code ResourceNotFoundException} for a table
     * that does not exist, so a referenced {@code TableName} is seeded with
     * {@code CreateTable}. The seed carries the minimal schema the emulators
     * require: one string hash key named {@code cov-probe-key}, matching the
     * key the synthesizer puts in item operations ({@code Key} maps are
     * synthesized with that entry and a string {@code AttributeValue}), on
     * on-demand billing so no throughput is needed.
     */
    public static DependencySeeder dynamoDb() {
        return new DependencySeeder(List.of(
                new SeedRule("TableName", "CreateTable", "TableName", TABLE_TEMPLATE,
                        java.util.Set.of("ImportTable"), "DeleteTable", "TableName")));
    }

    private static final JsonNode TABLE_TEMPLATE = parse("""
            {"AttributeDefinitions":[{"AttributeName":"cov-probe-key","AttributeType":"S"}],
             "KeySchema":[{"AttributeName":"cov-probe-key","KeyType":"HASH"}],
             "BillingMode":"PAY_PER_REQUEST"}""");

    private static JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every dependency referenced by {@code input}, in encounter order. */
    public List<Seed> seedsFor(JsonNode input) {
        List<Seed> out = new ArrayList<>();
        if (!rules.isEmpty()) {
            collect(input, out);
        }
        return out;
    }

    private void collect(JsonNode node, List<Seed> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                JsonNode value = e.getValue();
                if (value.isTextual()) {
                    for (SeedRule rule : rules) {
                        if (rule.triggerMember().equals(e.getKey())) {
                            out.add(new Seed(rule.seedOperation(), rule.seedInputMember(), value.asText(),
                                    rule.template(), rule.creators(),
                                    rule.deleteOperation(), rule.deleteInputMember()));
                        }
                    }
                }
                collect(value, out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
        }
    }
}
