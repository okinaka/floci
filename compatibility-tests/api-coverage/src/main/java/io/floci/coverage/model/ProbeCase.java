package io.floci.coverage.model;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

/**
 * One operation and one protocol-agnostic input to probe it with. A
 * {@link io.floci.coverage.encode.RequestEncoder} turns this into the
 * {@link Variant} that matches the operation's wire protocol.
 *
 * @param operation    Smithy operation to probe.
 * @param probe        Short name of the input shape used, for diagnostics
 *                     ({@code required-only}, {@code labels-only}).
 * @param logicalInput The synthesized input as a Jackson tree, structured like
 *                     the Smithy input shape. {@code null} or
 *                     {@code MissingNode} means "no input".
 */
public record ProbeCase(
        OperationShape operation,
        String probe,
        JsonNode logicalInput) {
}
