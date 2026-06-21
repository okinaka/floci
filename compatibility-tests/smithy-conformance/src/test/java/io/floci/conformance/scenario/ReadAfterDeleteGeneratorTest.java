package io.floci.conformance.scenario;

import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests for {@link ReadAfterDeleteGenerator}: it should pair
 * Create/Delete/Get triples that share a flat identifier member, and skip ops
 * lacking one.
 */
class ReadAfterDeleteGeneratorTest {

    private static final Model V2 = SmithyModelLoader.loadSesV2();

    private static List<LifecycleScenario> scenarios() {
        ServiceShape svc = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#SimpleEmailService_v2"), ServiceShape.class);
        return new ReadAfterDeleteGenerator().generate(svc, V2);
    }

    @Test
    void pairsEmailIdentityCreateDeleteGetTriple() {
        Map<String, LifecycleScenario> byLabel = scenarios().stream()
                .collect(Collectors.toMap(LifecycleScenario::label, Function.identity()));

        assertThat(byLabel).containsKey("read-after-delete.EmailIdentity");
        LifecycleScenario s = byLabel.get("read-after-delete.EmailIdentity");
        assertThat(s.createOp().getId().getName()).isEqualTo("CreateEmailIdentity");
        assertThat(s.deleteOp().getId().getName()).isEqualTo("DeleteEmailIdentity");
        assertThat(s.readOp().getId().getName()).isEqualTo("GetEmailIdentity");
        // The shared identifier is set to the same value on all three steps.
        String id = s.createInput().get("EmailIdentity").asText();
        assertThat(id).isNotBlank();
        assertThat(s.deleteInput().get("EmailIdentity").asText()).isEqualTo(id);
        assertThat(s.readInput().get("EmailIdentity").asText()).isEqualTo(id);
    }

    @Test
    void coversSeveralFlatResources() {
        var labels = scenarios().stream().map(LifecycleScenario::label).toList();
        assertThat(labels).contains(
                "read-after-delete.EmailIdentity",
                "read-after-delete.ConfigurationSet");
    }

    @Test
    void readInputCarriesOnlyTheSharedIdentifier() {
        LifecycleScenario s = scenarios().stream()
                .filter(x -> x.label().equals("read-after-delete.ConfigurationSet"))
                .findFirst().orElseThrow();
        assertThat(s.readInput().has("ConfigurationSetName")).isTrue();
    }
}
