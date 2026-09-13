package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;

import java.util.Map;

/**
 * Encoder for the Smithy RPC v2 CBOR protocol ({@code smithy.protocols#rpcv2Cbor},
 * e.g. CloudWatch). Like the AWS JSON family the protocol has no HTTP member
 * bindings — every input member rides in the body and the operation is selected
 * by the request path — so encoding is a pass-through of the logical input. The
 * {@link io.floci.conformance.invoke.RpcV2CborInvoker} serialises that JSON tree
 * to CBOR and targets {@code POST /service/{service}/operation/{operation}}.
 */
public final class RpcV2CborEncoder implements RequestEncoder {

    @Override
    public String protocol() {
        return "smithy.protocols#rpcv2Cbor";
    }

    @Override
    public Variant encode(GeneratedCase g) {
        JsonNode body = g.logicalInput();
        if (body != null && (body.isMissingNode() || body.isNull())) {
            body = null;
        }
        return new Variant(g.operation(), g.generator(),
                Map.of(), Map.of(), Map.of(),
                body, g.expectedOutcome(), g.expectedError());
    }
}
