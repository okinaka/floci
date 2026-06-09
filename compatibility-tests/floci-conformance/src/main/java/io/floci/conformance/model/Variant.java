package io.floci.conformance.model;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

import java.util.Collections;
import java.util.Map;

/**
 * A single conformance test case: one operation, one synthetic input produced by
 * one generator strategy, and a predicted outcome the runner will measure
 * against.
 *
 * <p>Variants are immutable value objects. Generators stamp them, runners
 * consume them, baselines aggregate them.
 *
 * @param operation       Smithy operation under test.
 * @param generator       Short name of the strategy that produced this variant
 *                        (e.g. {@code "optionals.required-only"}). Used in
 *                        baseline keys and per-PR diff reports.
 * @param pathParams      Resolved {@code @httpLabel} substitutions. Empty for
 *                        AWS-Query operations.
 * @param queryParams     Form-encoded params for AWS Query, plus any REST-JSON
 *                        {@code @httpQuery} bindings. Order is preserved.
 * @param headers         Custom HTTP headers (mostly REST-JSON {@code @httpHeader}).
 * @param jsonBody        REST-JSON request body, or {@code null} if no body.
 * @param expectedOutcome What this variant predicts Floci will return.
 * @param expectedError   For {@link ExpectedOutcome#CLIENT_ERROR} variants, the
 *                        Smithy error shape name (short, no namespace) the
 *                        runner should accept. {@code null} for SUCCESS variants.
 */
public record Variant(
        OperationShape operation,
        String generator,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        JsonNode jsonBody,
        ExpectedOutcome expectedOutcome,
        String expectedError) {

    public Variant {
        pathParams = pathParams == null ? Map.of() : Collections.unmodifiableMap(pathParams);
        queryParams = queryParams == null ? Map.of() : Collections.unmodifiableMap(queryParams);
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }

    public String operationName() {
        return operation.getId().getName();
    }
}
