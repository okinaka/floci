package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

/**
 * A two-step lifecycle invariant: create a resource, then list its kind and
 * assert the new resource shows up.
 *
 * <p>Like {@link LifecycleScenario}, this asserts only an internal relation
 * between operations ("what you create appears when you list"), so it needs no
 * Smithy oracle and no live-AWS reference. Membership is checked by looking for
 * the created resource's unique identifier value anywhere in the List response.
 *
 * @param label            Strategy label baked into reports
 *                         (e.g. {@code "list-after-create.EmailIdentity"}).
 * @param createOp         The {@code Create*} / {@code Put*} / {@code Add*} op.
 * @param createInput      Input for {@code createOp}.
 * @param listOp           The matching {@code List*} op (also the reporting op).
 * @param listInput        Input for {@code listOp} (usually empty).
 * @param identifierMember Top-level create-input member whose (post-salt) value
 *                         must appear in the list response.
 */
public record ListAfterCreateScenario(
        String label,
        OperationShape createOp,
        JsonNode createInput,
        OperationShape listOp,
        JsonNode listInput,
        String identifierMember) {
}
