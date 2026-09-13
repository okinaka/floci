package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

class RpcV2CborEncoderTest {

    private static final Model CW = SmithyModelLoader.loadCloudWatch();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static OperationShape op(String name) {
        return CW.expectShape(ShapeId.from("com.amazonaws.cloudwatch#" + name), OperationShape.class);
    }

    @Test
    void encodeIsPassThroughOfLogicalInput() throws Exception {
        JsonNode input = JSON.readTree("{\"Namespace\":\"ns\",\"MetricName\":\"m\"}");
        GeneratedCase c = new GeneratedCase(op("ListMetrics"), "x", input,
                ExpectedOutcome.SUCCESS, null);

        Variant v = new RpcV2CborEncoder().encode(c);

        assertThat(v.jsonBody()).isEqualTo(input);
        assertThat(v.pathParams()).isEmpty();
        assertThat(v.queryParams()).isEmpty();
        assertThat(v.headers()).isEmpty();
    }

    @Test
    void nullAndMissingInputBecomeNullBody() {
        Variant v = new RpcV2CborEncoder().encode(new GeneratedCase(
                op("ListMetrics"), "x", JSON.nullNode(), ExpectedOutcome.SUCCESS, null));
        assertThat(v.jsonBody()).isNull();
    }

    @Test
    void protocolIdIsRpcV2Cbor() {
        assertThat(new RpcV2CborEncoder().protocol()).isEqualTo("smithy.protocols#rpcv2Cbor");
    }

    @Test
    void cborWireRoundTripsThroughJackson() throws Exception {
        // Documents the wire contract the invoker relies on: JSON tree -> CBOR
        // bytes -> JSON tree is lossless for the value types used in inputs.
        JsonNode in = JSON.readTree(
                "{\"AlarmName\":\"a\",\"StateValue\":\"ALARM\",\"Dimensions\":[{\"Name\":\"n\",\"Value\":\"v\"}]}");
        CBORMapper cbor = new CBORMapper();
        byte[] bytes = cbor.writeValueAsBytes(in);
        JsonNode back = cbor.readTree(bytes);
        assertThat(back).isEqualTo(in);
    }
}
