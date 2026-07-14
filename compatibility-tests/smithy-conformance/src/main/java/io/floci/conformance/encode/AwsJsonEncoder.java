package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;

import java.util.Map;

/**
 * Encoder for the AWS JSON protocol family (1.0 / 1.1 — SSM, DynamoDB, KMS,
 * Secrets Manager, ...). The protocol has no HTTP bindings: every input
 * member rides in the JSON body, and the operation is selected by the
 * {@code X-Amz-Target} header the invoker adds. Encoding is therefore a
 * pass-through of the logical input; only the protocol identifier differs
 * between the minor versions.
 */
public final class AwsJsonEncoder implements RequestEncoder {

    private final String protocol;

    public AwsJsonEncoder(String protocol) {
        this.protocol = protocol;
    }

    /** AWS JSON 1.1 (SSM, KMS, Secrets Manager, EventBridge). */
    public static AwsJsonEncoder json11() {
        return new AwsJsonEncoder("aws.protocols#awsJson1_1");
    }

    /** AWS JSON 1.0 (DynamoDB, Step Functions). */
    public static AwsJsonEncoder json10() {
        return new AwsJsonEncoder("aws.protocols#awsJson1_0");
    }

    @Override
    public String protocol() {
        return protocol;
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
