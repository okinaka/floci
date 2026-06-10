package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;

import java.util.Map;

/**
 * Encoder for the AWS JSON 1.1 protocol (SSM, KMS, Secrets Manager, ...).
 * The protocol has no HTTP bindings: every input member rides in the JSON
 * body, and the operation is selected by the {@code X-Amz-Target} header the
 * invoker adds. Encoding is therefore a pass-through of the logical input.
 */
public final class AwsJson11Encoder implements RequestEncoder {

    private static final String PROTOCOL = "aws.protocols#awsJson1_1";

    @Override
    public String protocol() {
        return PROTOCOL;
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
