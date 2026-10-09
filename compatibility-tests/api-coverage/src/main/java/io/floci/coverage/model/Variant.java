package io.floci.coverage.model;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.shapes.OperationShape;

import java.util.Collections;
import java.util.Map;

/**
 * One request, encoded for the operation's wire protocol and ready to send.
 *
 * <p>Variants are immutable value objects: an encoder stamps them, an
 * {@link io.floci.coverage.invoke.Invoker} sends them.
 *
 * @param operation      Smithy operation being probed.
 * @param probe          Short name of the input shape used, carried over from
 *                       {@link ProbeCase} for diagnostics.
 * @param pathParams     Resolved {@code @httpLabel} substitutions. Empty for
 *                       AWS-Query operations.
 * @param queryParams    Form-encoded params for AWS Query, plus any REST-JSON
 *                       {@code @httpQuery} bindings. Order is preserved.
 * @param headers        Custom HTTP headers (mostly REST-JSON {@code @httpHeader}).
 * @param jsonBody       REST-JSON request body, or {@code null} if no body.
 * @param rawBody        Pre-serialized request body for protocols whose wire
 *                       format isn't JSON (REST XML payloads, raw blobs).
 *                       {@code null} when {@code jsonBody} drives the body.
 * @param rawContentType {@code Content-Type} accompanying {@code rawBody}.
 */
public record Variant(
        OperationShape operation,
        String probe,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        JsonNode jsonBody,
        String rawBody,
        String rawContentType) {

    public Variant {
        pathParams = pathParams == null ? Map.of() : Collections.unmodifiableMap(pathParams);
        queryParams = queryParams == null ? Map.of() : Collections.unmodifiableMap(queryParams);
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }

    /** Convenience for protocols without a raw body (Query, REST JSON). */
    public Variant(OperationShape operation, String probe,
                   Map<String, String> pathParams, Map<String, String> queryParams,
                   Map<String, String> headers, JsonNode jsonBody) {
        this(operation, probe, pathParams, queryParams, headers, jsonBody, null, null);
    }

    public String operationName() {
        return operation.getId().getName();
    }
}
