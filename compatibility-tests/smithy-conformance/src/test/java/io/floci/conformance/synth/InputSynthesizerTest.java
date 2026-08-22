package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link InputSynthesizer}: the required-only and
 * all-members member filters against a real SES v2 input structure.
 */
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

    @Test
    void numericValue_is_clamped_into_at_range_minimum() {
        Model model = SmithyModelLoader.loadSesV2();
        // DeliveryOptions.MaxDeliverySeconds carries @range{min:300,max:50400};
        // the default 1 must be lifted to the minimum so the input is in-range.
        StructureShape deliveryOptions = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#DeliveryOptions"), StructureShape.class);

        InputSynthesizer synth = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode tree = synth.synthesizeInput(deliveryOptions);

        assertThat(tree.get("MaxDeliverySeconds").asLong()).isEqualTo(300L);
    }

    @Test
    void numericValue_without_range_stays_at_default() {
        Model model = SmithyModelLoader.loadSesV2();
        // SendQuota.Max24HourSend is a Double with no @range — must stay at 1.0,
        // proving the clamp only fires when the model actually constrains it.
        StructureShape sendQuota = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#SendQuota"), StructureShape.class);

        InputSynthesizer synth = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode tree = synth.synthesizeInput(sendQuota);

        assertThat(tree.get("Max24HourSend").asDouble()).isEqualTo(1.0d);
    }
}
