package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.model.ExpectedOutcome;
import software.amazon.smithy.model.shapes.OperationShape;

/**
 * What a {@link Generator} produces: a protocol-agnostic logical input plus
 * the predicted outcome. The runner's {@code RequestEncoder} converts this
 * into a final {@code Variant} (path / query / headers / body) that matches
 * the operation's wire protocol.
 *
 * @param operation       Smithy operation under test.
 * @param generator       Short, stable strategy name (used in baseline keys).
 * @param logicalInput    The synthesized input as a Jackson tree, structured
 *                        like the Smithy input shape. {@code null} or
 *                        {@code MissingNode} means "no input".
 * @param expectedOutcome What this case predicts Floci will return.
 * @param expectedError   Short Smithy error shape name for CLIENT_ERROR cases,
 *                        or {@code null}.
 */
public record GeneratedCase(
        OperationShape operation,
        String generator,
        JsonNode logicalInput,
        ExpectedOutcome expectedOutcome,
        String expectedError) {
}
