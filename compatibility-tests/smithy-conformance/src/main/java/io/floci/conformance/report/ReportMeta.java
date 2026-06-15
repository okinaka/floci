package io.floci.conformance.report;

/**
 * Context attached to a report — service identifier, when it was generated,
 * and which Smithy model version the harness ran against.
 *
 * @param serviceShapeId Smithy ID of the service under test
 *                       (e.g. {@code com.amazonaws.ses#SimpleEmailService}).
 * @param modelVersion   Service-version string from the Smithy model
 *                       (e.g. {@code 2010-12-01}).
 * @param generatedAt    ISO-8601 timestamp.
 */
public record ReportMeta(String serviceShapeId, String modelVersion, String generatedAt) {
}
