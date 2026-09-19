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
 * Offline tests for {@link ListAfterCreateGenerator}: it pairs Create with the
 * pluralised List op and pins the identifier member to search for.
 */
class ListAfterCreateGeneratorTest {

    private static final Model V2 = SmithyModelLoader.loadSesV2();

    private static List<ListAfterCreateScenario> scenarios() {
        ServiceShape svc = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#SimpleEmailService_v2"), ServiceShape.class);
        return new ListAfterCreateGenerator().generate(svc, V2);
    }

    @Test
    void pairsEmailIdentityCreateWithListEmailIdentities() {
        Map<String, ListAfterCreateScenario> byLabel = scenarios().stream()
                .collect(Collectors.toMap(ListAfterCreateScenario::label, Function.identity()));

        assertThat(byLabel).containsKey("list-after-create.EmailIdentity");
        ListAfterCreateScenario s = byLabel.get("list-after-create.EmailIdentity");
        assertThat(s.createOp().getId().getName()).isEqualTo("CreateEmailIdentity");
        assertThat(s.listOp().getId().getName()).isEqualTo("ListEmailIdentities");
        assertThat(s.identifierMember()).isEqualTo("EmailIdentity");
        // The identifier value is set on the create input so it can be searched for.
        assertThat(s.createInput().get("EmailIdentity").asText()).isNotBlank();
    }

    @Test
    void pluraliserHandlesYToIesAndPlainS() {
        var labels = scenarios().stream().map(ListAfterCreateScenario::label).toList();
        // EmailIdentity -> ListEmailIdentities (y->ies), ConfigurationSet -> ListConfigurationSets (+s)
        assertThat(labels).contains(
                "list-after-create.EmailIdentity",
                "list-after-create.ConfigurationSet");
    }
}
