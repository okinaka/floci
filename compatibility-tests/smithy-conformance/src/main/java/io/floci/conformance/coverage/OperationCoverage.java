package io.floci.conformance.coverage;

/**
 * One operation's probe outcome.
 *
 * @param service    api-models-aws directory name (e.g. {@code dynamodb}).
 * @param operation  Smithy operation name.
 * @param protocol   Protocol the deciding probe used, short form (e.g. {@code awsJson1_0}).
 * @param status     Verdict.
 * @param httpStatus HTTP status of the deciding probe, or {@code -1} if none.
 * @param errorType  Normalized error type, or {@code null}.
 */
public record OperationCoverage(
        String service,
        String operation,
        String protocol,
        CoverageStatus status,
        int httpStatus,
        String errorType) {
}
