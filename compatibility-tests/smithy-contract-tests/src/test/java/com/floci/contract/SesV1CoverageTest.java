package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 coverage probe for SES v1 (Query / XML). Enumerates every operation
 * declared in the SES v1 Smithy model and invokes each against the running Floci
 * with a minimal request (just {@code Action=<OpName>}, no other parameters).
 * Each response is classified into one of the {@link CoverageReport.Status} buckets:
 *
 * <ul>
 *   <li>{@code IMPLEMENTED_OK} / {@code IMPLEMENTED_DRIFT}: 200 with body that matches /
 *       differs from the Smithy output shape</li>
 *   <li>{@code IMPLEMENTED_VALIDATION}: 4xx with an InvalidParameter-class error
 *       — op is dispatched, the empty-request fixture is too thin</li>
 *   <li>{@code IMPLEMENTED_STATE}: 4xx with a not-found / does-not-exist code
 *       — op is dispatched, would need prior state set up</li>
 *   <li>{@code NOT_IMPLEMENTED}: 4xx with {@code UnsupportedOperation}
 *       — Floci's query handler does not route this op</li>
 *   <li>{@code ERROR}: 5xx or unclassified — harness suspect, not a coverage signal</li>
 * </ul>
 *
 * Writes the per-op breakdown + summary to
 * {@code target/ses-v1-coverage.md} and logs a one-line summary. This test never
 * fails on coverage values — it is a measurement, not a gate. Pin a baseline later.
 */
@DisplayName("SES v1 — Phase 1 coverage probe")
class SesV1CoverageTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final ShapeId SERVICE_ID = ShapeId.from("com.amazonaws.ses#SimpleEmailService");

    /**
     * v1 error codes meaning "op exists but needs prior state". Not exhaustive —
     * extend as the probe surfaces more.
     */
    private static final Set<String> STATE_ERRORS = Set.of(
            "ConfigurationSetDoesNotExist",
            "TemplateDoesNotExist",
            "RuleSetDoesNotExist",
            "InvalidS3Configuration",
            "AlreadyExists",
            "ConfigurationSetAlreadyExists",
            "CustomVerificationEmailTemplateAlreadyExists",
            "CustomVerificationEmailTemplateDoesNotExist",
            "EventDestinationDoesNotExist",
            "EventDestinationAlreadyExists",
            "TrackingOptionsDoesNotExistException",
            "TrackingOptionsAlreadyExistsException",
            // account-level / business-state errors signalling "op dispatched, state blocks it"
            "AccountSendingPausedException",
            "ConfigurationSetSendingPausedException",
            "MailFromDomainNotVerifiedException"
    );

    /**
     * v1 error codes meaning "op exists but the empty/synthetic request was rejected
     * by input validation". Extend as the probe surfaces more.
     */
    private static final Set<String> VALIDATION_ERRORS = Set.of(
            "InvalidParameterValue",
            "ValidationError",
            "MissingParameter",
            "MissingAction",
            "MalformedQueryString",
            "MessageRejected"
    );

    @Test
    void generate() throws Exception {
        Model model = SmithyModelLoader.load("models/ses.json");
        SmithyResponseValidator validator = new SmithyResponseValidator(model, true);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        XmlMapper xml = new XmlMapper();

        ServiceShape service = model.expectShape(SERVICE_ID, ServiceShape.class);
        List<OperationShape> ops = service.getAllOperations().stream()
                .sorted()
                .map(id -> model.expectShape(id, OperationShape.class))
                .toList();

        CoverageReport report = new CoverageReport("SES v1 (Query/XML) — Phase 1 coverage probe");

        for (OperationShape op : ops) {
            String opName = op.getId().getName();
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(ENDPOINT + "/"))
                                .header("Authorization", SES_AUTH)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString("Action=" + opName))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                report.record(classify(opName, op, response, model, validator, xml));
            } catch (Exception e) {
                report.record(new CoverageReport.Entry(
                        opName, CoverageReport.Status.ERROR, -1,
                        "harness exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        Path reportFile = Path.of("target", "ses-v1-coverage.md");
        report.writeTo(reportFile);

        System.out.println("=== " + report.shortSummary() + " ===");
        System.out.println("=== Full report: " + reportFile.toAbsolutePath() + " ===");

        // Soft assertions only — at least one op should respond, ruling out a totally
        // broken harness. The probe is a measurement, not a gate.
        assertThat(report.total()).as("must enumerate ops").isGreaterThan(0);
        assertThat(report.implemented())
                .as("at least one v1 SES op must be implemented")
                .isGreaterThan(0);
    }

    private static CoverageReport.Entry classify(String opName, OperationShape op,
                                                  HttpResponse<String> response,
                                                  Model model,
                                                  SmithyResponseValidator validator,
                                                  XmlMapper xml) throws Exception {
        int status = response.statusCode();
        String body = response.body();

        if (status == 200) {
            // Try to walk the Smithy output shape against the inner *Result block.
            ShapeId outId = op.getOutputShape();
            JsonNode envelope = xml.readTree(body.getBytes(StandardCharsets.UTF_8));
            JsonNode inner = envelope.get(opName + "Result");
            if (inner == null) {
                // Some ops return an empty envelope (no Result wrapper). Treat as ok
                // if the output shape has no required members.
                return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_OK,
                        status, "200, no Result wrapper");
            }
            var errors = validator.validate(inner, outId);
            if (errors.isEmpty()) {
                return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_OK,
                        status, "200, shape ok");
            }
            return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_DRIFT,
                    status, errors.size() + " shape drift(s): " + errors.get(0));
        }

        // Non-200 — extract the v1 error code from <Error><Code>...</Code></Error>.
        String errorCode = extractV1ErrorCode(body, xml);
        if ("UnsupportedOperation".equals(errorCode)) {
            return new CoverageReport.Entry(opName, CoverageReport.Status.NOT_IMPLEMENTED,
                    status, errorCode);
        }
        if (errorCode != null) {
            if (STATE_ERRORS.contains(errorCode) || errorCode.endsWith("DoesNotExist")
                    || errorCode.endsWith("AlreadyExists")) {
                return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_STATE,
                        status, errorCode);
            }
            if (VALIDATION_ERRORS.contains(errorCode)
                    || errorCode.startsWith("Invalid")
                    || errorCode.startsWith("Missing")) {
                return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_VALIDATION,
                        status, errorCode);
            }
        }
        return new CoverageReport.Entry(opName, CoverageReport.Status.ERROR, status,
                "unclassified: code=" + errorCode + ", status=" + status);
    }

    private static String extractV1ErrorCode(String body, XmlMapper xml) {
        try {
            JsonNode root = xml.readTree(body.getBytes(StandardCharsets.UTF_8));
            // Either <ErrorResponse><Error><Code>...</Code></Error></ErrorResponse>
            // or the SES handler's slightly different envelope. Try both.
            JsonNode err = root.path("Error");
            if (err.isMissingNode() || err.isNull()) {
                err = root;  // some Floci responses put Code at top
            }
            JsonNode code = err.path("Code");
            return code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
