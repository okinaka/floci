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

class RoundTripEchoGeneratorTest {

    private static final Model V2 = SmithyModelLoader.loadSesV2();
    private static final ServiceShape V2_SERVICE = V2.expectShape(
            ShapeId.from("com.amazonaws.sesv2#SimpleEmailService_v2"), ServiceShape.class);

    @Test
    void pairs_create_and_get_email_identity() {
        List<RoundTripScenario> scenarios = new RoundTripEchoGenerator().generate(V2_SERVICE, V2);

        RoundTripScenario emailIdScenario = scenarios.stream()
                .filter(s -> s.setupOp().getId().getName().equals("CreateEmailIdentity"))
                .findFirst()
                .orElseThrow();
        assertThat(emailIdScenario.verifyOp().getId().getName()).isEqualTo("GetEmailIdentity");
        // GetEmailIdentity's response carries Tags / ConfigurationSetName but
        // not EmailIdentity itself (it's an @httpLabel on input). The echoed
        // paths thus exclude the identifier field.
        assertThat(emailIdScenario.echoedPaths()).isNotEmpty();
        assertThat(emailIdScenario.setupInput().get("EmailIdentity").asText())
                .isEqualTo("cov-probe-rt-EmailIdentity");
        assertThat(emailIdScenario.verifyInput().get("EmailIdentity").asText())
                .isEqualTo("cov-probe-rt-EmailIdentity");
    }

    @Test
    void picks_up_email_template_pair() {
        List<RoundTripScenario> scenarios = new RoundTripEchoGenerator().generate(V2_SERVICE, V2);
        Set<String> setupOps = scenarios.stream()
                .map(s -> s.setupOp().getId().getName())
                .collect(Collectors.toSet());
        assertThat(setupOps).contains("CreateEmailTemplate");
    }

    @Test
    void verify_input_carries_only_identifier_field() {
        List<RoundTripScenario> scenarios = new RoundTripEchoGenerator().generate(V2_SERVICE, V2);
        RoundTripScenario s = scenarios.stream()
                .filter(x -> x.setupOp().getId().getName().equals("CreateEmailIdentity"))
                .findFirst().orElseThrow();
        assertThat(s.verifyInput().size()).isEqualTo(1);
    }

    @Test
    void trim_setup_prefix_recognizes_create_put_add() {
        assertThat(RoundTripEchoGenerator.trimSetupPrefix("CreateFoo")).isEqualTo("Foo");
        assertThat(RoundTripEchoGenerator.trimSetupPrefix("PutBar")).isEqualTo("Bar");
        assertThat(RoundTripEchoGenerator.trimSetupPrefix("AddBaz")).isEqualTo("Baz");
        assertThat(RoundTripEchoGenerator.trimSetupPrefix("DescribeX")).isNull();
        assertThat(RoundTripEchoGenerator.trimSetupPrefix("Create")).isNull(); // no suffix
    }

    @Test
    void scenarios_include_configuration_set_via_top_level_name() {
        // v2 CreateConfigurationSet has ConfigurationSetName at top level; verify
        // op GetConfigurationSet also takes ConfigurationSetName — shared, paired.
        List<RoundTripScenario> scenarios = new RoundTripEchoGenerator().generate(V2_SERVICE, V2);
        RoundTripScenario configScenario = scenarios.stream()
                .filter(s -> s.setupOp().getId().getName().equals("CreateConfigurationSet"))
                .findFirst().orElseThrow();
        assertThat(configScenario.verifyOp().getId().getName()).isEqualTo("GetConfigurationSet");
        assertThat(configScenario.setupInput().get("ConfigurationSetName").asText())
                .isEqualTo("cov-probe-rt-ConfigurationSet");
    }
}
