package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

import java.util.List;

/**
 * A two-step conformance scenario: a setup op writes data, then a verify op
 * reads it back. The runner asserts that each field in {@link #echoedPaths()}
 * survives the round-trip — present in the verify response and equal to the
 * value the setup op was given.
 *
 * @param generatorName Strategy label baked into reports (e.g.
 *                      {@code "round-trip-echo.EmailIdentity"}).
 * @param primaryOp     The op the case "belongs to" for reporting (typically
 *                      the verify op — the one whose response is asserted).
 * @param setupOp       The {@code Create*} / {@code Put*} that seeds state.
 * @param setupInput    Input passed to {@code setupOp}.
 * @param verifyOp      The {@code Get*} / {@code Describe*} that reads back.
 * @param verifyInput   Input passed to {@code verifyOp}.
 * @param echoedPaths   Top-level fields expected to appear identically in
 *                      both {@code setupInput} and the verify response body.
 */
public record RoundTripScenario(
        String generatorName,
        OperationShape primaryOp,
        OperationShape setupOp,
        JsonNode setupInput,
        OperationShape verifyOp,
        JsonNode verifyInput,
        List<String> echoedPaths) {

    public RoundTripScenario {
        echoedPaths = List.copyOf(echoedPaths);
    }
}
