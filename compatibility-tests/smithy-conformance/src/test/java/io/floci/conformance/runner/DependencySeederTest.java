package io.floci.conformance.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.conformance.runner.DependencySeeder.Seed;
import io.floci.conformance.runner.DependencySeeder.SeedRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencySeederTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DependencySeeder SES = DependencySeeder.sesV2();

    private static com.fasterxml.jackson.databind.JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Single-rule seeder so mechanism tests don't depend on the factory's evolving ruleset. */
    private static final DependencySeeder DOMAIN_RULE = new DependencySeeder(List.of(
            new SeedRule("CustomRedirectDomain", "CreateEmailIdentity", "EmailIdentity")));

    @Test
    void findsTriggerMemberNestedInInput() {
        var seeds = DOMAIN_RULE.seedsFor(tree("""
                {"ConfigurationSetName":"cs","TrackingOptions":{"CustomRedirectDomain":"d.example.com"}}"""));
        assertThat(seeds).containsExactly(
                new Seed("CreateEmailIdentity", "EmailIdentity", "d.example.com"));
    }

    @Test
    void emitsOneSeedPerOccurrenceAcrossArraysAndNesting() {
        var seeds = SES.seedsFor(tree("""
                {"a":[{"CustomRedirectDomain":"one.example.com"},
                      {"b":{"CustomRedirectDomain":"two.example.com"}}]}"""));
        assertThat(seeds).extracting(Seed::value)
                .containsExactly("one.example.com", "two.example.com");
    }

    @Test
    void ignoresInputsWithoutTheTriggerMember() {
        assertThat(DOMAIN_RULE.seedsFor(tree("""
                {"ConfigurationSetName":"cs","TrackingOptions":{"HttpsPolicy":"REQUIRE"}}""")))
                .isEmpty();
    }

    @Test
    void sesV2FactorySeedsConfigurationSetAndDomainAndPool() {
        var seeds = DependencySeeder.sesV2().seedsFor(tree("""
                {"ConfigurationSetName":"cs",
                 "TrackingOptions":{"CustomRedirectDomain":"d.example.com"},
                 "DeliveryOptions":{"SendingPoolName":"pool-1"}}"""));
        assertThat(seeds).containsExactlyInAnyOrder(
                new Seed("CreateConfigurationSet", "ConfigurationSetName", "cs"),
                new Seed("CreateEmailIdentity", "EmailIdentity", "d.example.com"),
                new Seed("CreateDedicatedIpPool", "PoolName", "pool-1"));
    }

    @Test
    void sesV1FactorySeedsIdentityViaVerify() {
        assertThat(DependencySeeder.sesV1().seedsFor(tree("""
                {"Identity":"user@example.com","ForwardingEnabled":true}""")))
                .containsExactly(new Seed("VerifyEmailIdentity", "EmailAddress", "user@example.com"));
    }

    @Test
    void sesV1FactorySeedsConfigurationSetNestedAndDomain() {
        var seeds = DependencySeeder.sesV1().seedsFor(tree("""
                {"ConfigurationSetName":"cs",
                 "TrackingOptions":{"CustomRedirectDomain":"d.example.com"}}"""));
        // The nested ConfigurationSet.Name path is carried verbatim on the Seed;
        // the runner expands it into {ConfigurationSet:{Name:...}} at send time.
        assertThat(seeds).containsExactlyInAnyOrder(
                new Seed("CreateConfigurationSet", "ConfigurationSet.Name", "cs"),
                new Seed("VerifyDomainIdentity", "Domain", "d.example.com"));
    }

    @Test
    void ignoresNonTextualTriggerValues() {
        assertThat(SES.seedsFor(tree("""
                {"CustomRedirectDomain":{"nested":"x"}}"""))).isEmpty();
    }

    @Test
    void noneSeederNeverSeeds() {
        assertThat(DependencySeeder.NONE.seedsFor(tree("""
                {"CustomRedirectDomain":"d.example.com"}"""))).isEmpty();
    }

    @Test
    void customRulesMatchByMemberName() {
        DependencySeeder seeder = new DependencySeeder(List.of(
                new SeedRule("SendingPoolName", "CreateDedicatedIpPool", "PoolName")));
        assertThat(seeder.seedsFor(tree("""
                {"DeliveryOptions":{"SendingPoolName":"pool-1"}}""")))
                .containsExactly(new Seed("CreateDedicatedIpPool", "PoolName", "pool-1"));
    }

    @Test
    void nullInputYieldsNoSeeds() {
        assertThat(SES.seedsFor(null)).isEmpty();
    }

    @Test
    void dynamoDbFactorySeedsTableWithKeySchemaTemplate() {
        var seeds = DependencySeeder.dynamoDb().seedsFor(tree("""
                {"TableName":"cov-probe-t","Key":{"cov-probe-key":{"S":"x"}}}"""));
        assertThat(seeds).hasSize(1);
        Seed seed = seeds.get(0);
        assertThat(seed.operation()).isEqualTo("CreateTable");
        assertThat(seed.inputMember()).isEqualTo("TableName");
        assertThat(seed.value()).isEqualTo("cov-probe-t");
        assertThat(seed.template().get("KeySchema").get(0).get("AttributeName").asText())
                .isEqualTo("cov-probe-key");
        assertThat(seed.template().get("BillingMode").asText()).isEqualTo("PAY_PER_REQUEST");
        assertThat(seed.createdBy("CreateTable")).isTrue();
        assertThat(seed.createdBy("ImportTable")).isTrue();
        assertThat(seed.createdBy("DescribeTable")).isFalse();
        assertThat(seed.deleteOperation()).isEqualTo("DeleteTable");
        assertThat(seed.deleteInputMember()).isEqualTo("TableName");
    }

    @Test
    void s3FactorySeedsBucketAndCanUnseedIt() {
        var seeds = DependencySeeder.s3().seedsFor(tree("""
                {"Bucket":"cov-probe-bucket","Key":"k"}"""));
        assertThat(seeds).hasSize(1);
        Seed seed = seeds.get(0);
        assertThat(seed.operation()).isEqualTo("CreateBucket");
        assertThat(seed.inputMember()).isEqualTo("Bucket");
        assertThat(seed.value()).isEqualTo("cov-probe-bucket");
        assertThat(seed.template()).isNull();
        // CreateBucket creates it itself, so the runner unseeds instead of seeding.
        assertThat(seed.createdBy("CreateBucket")).isTrue();
        assertThat(seed.createdBy("PutBucketTagging")).isFalse();
        assertThat(seed.deleteOperation()).isEqualTo("DeleteBucket");
    }

    @Test
    void sesV2FactorySeedsTenant() {
        var seeds = DependencySeeder.sesV2().seedsFor(tree("""
                {"TenantName":"cov-probe-t"}"""));
        assertThat(seeds).hasSize(1);
        Seed tenant = seeds.get(0);
        assertThat(tenant.operation()).isEqualTo("CreateTenant");
        assertThat(tenant.inputMember()).isEqualTo("TenantName");
        assertThat(tenant.value()).isEqualTo("cov-probe-t");
        assertThat(tenant.template()).isNull();
        assertThat(tenant.deleteOperation()).isEqualTo("DeleteTenant");
        // CreateTenant makes the tenant itself, so the runner unseeds instead.
        assertThat(tenant.createdBy("CreateTenant")).isTrue();
        assertThat(tenant.createdBy("SendEmail")).isFalse();
    }

    @Test
    void sesV2FactorySeedsTheAccountContactList() {
        // Safe only because NameSalt treats ContactListName as an account
        // singleton, so the seeded list is the one every case references.
        var seeds = DependencySeeder.sesV2().seedsFor(tree("""
                {"ContactListName":"cov-probe-l"}"""));
        assertThat(seeds).hasSize(1);
        Seed list = seeds.get(0);
        assertThat(list.operation()).isEqualTo("CreateContactList");
        assertThat(list.inputMember()).isEqualTo("ContactListName");
        assertThat(list.deleteOperation()).isEqualTo("DeleteContactList");
        assertThat(list.createdBy("CreateContactList")).isTrue();
        assertThat(list.createdBy("CreateContact")).isFalse();
    }

    @Test
    void nameOnlyRulesCarryNoTemplate() {
        assertThat(DependencySeeder.sesV2().seedsFor(tree("""
                {"ConfigurationSetName":"cs"}""")).get(0).template()).isNull();
    }
}
