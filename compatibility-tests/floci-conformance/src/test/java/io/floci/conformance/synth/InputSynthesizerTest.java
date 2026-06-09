package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import static org.assertj.core.api.Assertions.assertThat;

class InputSynthesizerTest {

    @Test
    void requiredOnly_yields_only_required_members() {
        Model model = SmithyModelLoader.loadSesV2();
        StructureShape input = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#CreateEmailIdentityRequest"),
                StructureShape.class);

        InputSynthesizer synth = new InputSynthesizer(model, InputSynthesizer.requiredOnly(), null);
        ObjectNode tree = synth.synthesizeInput(input);

        // EmailIdentity is @required
        assertThat(tree.has("EmailIdentity")).isTrue();
        // Tags is optional — should NOT be present
        assertThat(tree.has("Tags")).isFalse();
    }

    @Test
    void allMembers_includes_both_required_and_optional() {
        Model model = SmithyModelLoader.loadSesV2();
        StructureShape input = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#CreateEmailIdentityRequest"),
                StructureShape.class);

        InputSynthesizer synth = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode tree = synth.synthesizeInput(input);

        assertThat(tree.has("EmailIdentity")).isTrue();
        assertThat(tree.has("Tags")).isTrue();
    }
}
