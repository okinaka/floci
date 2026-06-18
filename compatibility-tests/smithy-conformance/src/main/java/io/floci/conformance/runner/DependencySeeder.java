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
     */
    public record SeedRule(String triggerMember, String seedOperation, String seedInputMember) {
    }

    /** A concrete dependency discovered in a case input: seed {@code value} via {@code operation}. */
    public record Seed(String operation, String inputMember, String value) {
    }

    /** No rules — the common case for services with no cross-resource dependencies. */
    public static final DependencySeeder NONE = new DependencySeeder(List.of());

    private final List<SeedRule> rules;

    public DependencySeeder(List<SeedRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * SESv2 rules: a configuration set's custom redirect/tracking domain must be
     * a verified email identity, so seed it with {@code CreateEmailIdentity}
     * (which Floci auto-verifies) before the referencing operation runs.
     */
    public static DependencySeeder sesV2() {
        return new DependencySeeder(List.of(
                new SeedRule("CustomRedirectDomain", "CreateEmailIdentity", "EmailIdentity")));
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
                            out.add(new Seed(rule.seedOperation(), rule.seedInputMember(), value.asText()));
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
