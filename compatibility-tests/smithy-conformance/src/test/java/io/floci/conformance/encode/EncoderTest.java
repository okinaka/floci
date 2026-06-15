package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for the wire encoders: {@link QueryFormEncoder} dotted
 * form-param emission (lists as {@code Name.member.N}) and
 * {@link RestJsonEncoder} member splitting across path / query / header /
 * body by HTTP binding trait.
 */
class EncoderTest {

    @Test
    void queryEncoder_emits_dotted_form_params_for_lists() {
        Model model = SmithyModelLoader.loadSesV1();
        OperationShape op = model.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityVerificationAttributes"),
                OperationShape.class);

        // GetIdentityVerificationAttributes has Identities: List<Identity>
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.putArray("Identities").add("example.com").add("foo@example.com");

        Variant v = new QueryFormEncoder(model).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.queryParams()).containsEntry("Identities.member.1", "example.com");
        assertThat(v.queryParams()).containsEntry("Identities.member.2", "foo@example.com");
    }

    @Test
    void queryEncoder_emits_scalar_param() {
        Model model = SmithyModelLoader.loadSesV1();
        OperationShape op = model.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentities"), OperationShape.class);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("IdentityType", "Domain");
        input.put("MaxItems", 10);

        Variant v = new QueryFormEncoder(model).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.queryParams()).containsEntry("IdentityType", "Domain");
        assertThat(v.queryParams()).containsEntry("MaxItems", "10");
    }

    @Test
    void restJsonEncoder_splits_httpLabel_into_path() {
        Model model = SmithyModelLoader.loadSesV2();
        // GetEmailIdentity has EmailIdentity as @httpLabel
        OperationShape op = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#GetEmailIdentity"), OperationShape.class);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("EmailIdentity", "example.com");

        Variant v = new RestJsonEncoder(model).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.pathParams()).containsEntry("EmailIdentity", "example.com");
        assertThat(v.jsonBody()).isNull();
    }

    @Test
    void restJsonEncoder_puts_non_bound_members_in_body() {
        Model model = SmithyModelLoader.loadSesV2();
        // CreateEmailIdentity has EmailIdentity (regular field, not @httpLabel)
        OperationShape op = model.expectShape(
                ShapeId.from("com.amazonaws.sesv2#CreateEmailIdentity"), OperationShape.class);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("EmailIdentity", "example.com");

        Variant v = new RestJsonEncoder(model).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.pathParams()).isEmpty();
        assertThat(v.jsonBody()).isNotNull();
        assertThat(v.jsonBody().get("EmailIdentity").asText()).isEqualTo("example.com");
    }
}
