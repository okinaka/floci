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

    @Test
    void findsTriggerMemberNestedInInput() {
        var seeds = SES.seedsFor(tree("""
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
        assertThat(SES.seedsFor(tree("""
                {"ConfigurationSetName":"cs","TrackingOptions":{"HttpsPolicy":"REQUIRE"}}""")))
                .isEmpty();
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
}
