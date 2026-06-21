package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

/**
 * A three-step lifecycle invariant: create a resource, delete it, then read it
 * back and assert it is gone.
 *
 * <p>Unlike the model-shape and example checks, this asserts only an internal
 * relationship between operations — "what you delete you can no longer read" —
 * so it needs no Smithy oracle and no live-AWS reference. A read that still
 * succeeds after the delete is therefore a definite state bug.
 *
 * @param label       Strategy label baked into reports (e.g.
 *                    {@code "read-after-delete.EmailIdentity"}).
 * @param readOp      The {@code Get*} read-back op (also the reporting op).
 * @param createOp    The {@code Create*} / {@code Put*} / {@code Add*} that seeds state.
 * @param createInput Input for {@code createOp}.
 * @param deleteOp    The {@code Delete*} that removes the resource.
 * @param deleteInput Input for {@code deleteOp}.
 * @param readInput   Input for {@code readOp} (same identifier as create/delete).
 */
public record LifecycleScenario(
        String label,
        OperationShape readOp,
        OperationShape createOp,
        JsonNode createInput,
        OperationShape deleteOp,
        JsonNode deleteInput,
        JsonNode readInput) {
}
