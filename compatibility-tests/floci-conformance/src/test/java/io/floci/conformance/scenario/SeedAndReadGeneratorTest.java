package io.floci.conformance.scenario;

import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeedAndReadGeneratorTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();
    private static final ServiceShape V1_SERVICE = V1.expectShape(
            ShapeId.from("com.amazonaws.ses#SimpleEmailService"), ServiceShape.class);

    @Test
    void prefix_match_recognizes_setup_and_verify_verbs() {
        assertThat(SeedAndReadGenerator.findPrefix("CreateFoo", List.of("Create", "Put")))
                .isEqualTo("Create");
        assertThat(SeedAndReadGenerator.findPrefix("VerifyDomainIdentity",
                List.of("Create", "Put", "Add", "Verify"))).isEqualTo("Verify");
        assertThat(SeedAndReadGenerator.findPrefix("DescribeX",
                List.of("Get", "Describe", "List"))).isEqualTo("Describe");
        assertThat(SeedAndReadGenerator.findPrefix("SendEmail", List.of("Create"))).isNull();
    }

    @Test
    void emits_scenarios_for_v1_create_event_destination_pair() {
        List<RoundTripScenario> scenarios =
                new SeedAndReadGenerator().generate(V1_SERVICE, V1);

        // CreateConfigurationSetEventDestination shares ConfigurationSetName with
        // DescribeConfigurationSet — they're not the same-resource Create/Get pair
        // RoundTripEcho would catch, so this generator must pick it up.
        var hit = scenarios.stream()
                .filter(s -> s.setupOp().getId().getName()
                        .equals("CreateConfigurationSetEventDestination"))
                .filter(s -> s.verifyOp().getId().getName()
                        .equals("DescribeConfigurationSet"))
                .findFirst();
        assertThat(hit).isPresent();
        assertThat(hit.get().setupInput().get("ConfigurationSetName").asText())
                .isEqualTo(hit.get().verifyInput().get("ConfigurationSetName").asText());
    }

    @Test
    void emits_put_to_get_pair_for_identity_policy() {
        List<RoundTripScenario> scenarios =
                new SeedAndReadGenerator().generate(V1_SERVICE, V1);

        var hit = scenarios.stream()
                .filter(s -> s.setupOp().getId().getName().equals("PutIdentityPolicy"))
                .filter(s -> s.verifyOp().getId().getName().equals("GetIdentityPolicies"))
                .findFirst();
        assertThat(hit).isPresent();
        // Identity is shared scalar — value matches across setup and verify.
        assertThat(hit.get().setupInput().get("Identity").asText())
                .isEqualTo(hit.get().verifyInput().get("Identity").asText());
    }

    @Test
    void skips_pair_already_owned_by_RoundTripEcho() {
        List<RoundTripScenario> scenarios =
                new SeedAndReadGenerator().generate(V1_SERVICE, V1);
        // CreateReceiptRuleSet ↔ GetReceiptRuleSet doesn't exist in v1 (there's
        // only Describe*), so the suffix pair RoundTripEcho would build is
        // Create/CustomVerificationEmailTemplate ↔ Get/CustomVerificationEmailTemplate.
        // SeedAndRead must not duplicate it.
        boolean dup = scenarios.stream().anyMatch(s ->
                s.setupOp().getId().getName().equals("CreateCustomVerificationEmailTemplate")
                        && s.verifyOp().getId().getName()
                            .equals("GetCustomVerificationEmailTemplate"));
        assertThat(dup).isFalse();
    }

    @Test
    void scenarios_are_unique_by_label() {
        List<RoundTripScenario> scenarios =
                new SeedAndReadGenerator().generate(V1_SERVICE, V1);
        Set<String> labels = scenarios.stream()
                .map(RoundTripScenario::generatorName)
                .collect(Collectors.toSet());
        assertThat(labels).hasSize(scenarios.size());
    }
}
