package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpTrait;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 coverage probe for SES v2 (REST JSON). Enumerates every operation in
 * the SES v2 Smithy model, resolves the {@code @http} method + URI template, fills
 * each {@code {pathParam}} with a synthetic placeholder, and sends the request
 * with an empty body (POST/PUT) or no body (GET/DELETE).
 *
 * <p>Classification mirrors {@link SesV1CoverageTest} but the wire is JSON:
 * <ul>
 *   <li>200 → walk Smithy output shape</li>
 *   <li>404 {@code NotFoundException} with body referencing the synthetic resource
 *       → {@code IMPLEMENTED_STATE} (op routed, resource doesn't exist in probe)</li>
 *   <li>400 {@code BadRequestException} / validation-class → {@code IMPLEMENTED_VALIDATION}</li>
 *   <li>404 with empty or {@code RESTEASY*} body, or 405 Method Not Allowed →
 *       {@code NOT_IMPLEMENTED} (no JAX-RS route)</li>
 *   <li>4xx with state-suggesting error code → {@code IMPLEMENTED_STATE}</li>
 *   <li>other 4xx / 5xx → {@code ERROR}</li>
 * </ul>
 *
 * Writes {@code target/ses-v2-coverage.md} and logs a one-line summary.
 */
@DisplayName("SES v2 — Phase 1 coverage probe")
class SesV2CoverageTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final ShapeId SERVICE_ID = ShapeId.from(
            "com.amazonaws.sesv2#SimpleEmailService_v2");

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}]+?)}");

    /**
     * Synthetic path-param value. URL-encoded; safe for any RFC 3986 path segment.
     * Picking something obviously non-existent so 404 from real "resource not
     * found" lookups is unambiguous.
     */
    private static final String SYNTHETIC = "cov-probe-x";

    private static final Set<String> STATE_ERROR_CODES = Set.of(
            "NotFoundException",
            "ConfigurationSetDoesNotExist",
            "MessageRejected",
            "AccountSendingPausedException",
            "ConfigurationSetSendingPausedException",
            "MailFromDomainNotVerifiedException",
            "AlreadyExistsException",
            "ConflictException"
    );

    private static final Set<String> VALIDATION_ERROR_CODES = Set.of(
            "BadRequestException",
            "InvalidNextToken",
            "InvalidParameterValue",
            "ValidationException"
    );

    @Test
    void generate() throws Exception {
        Model model = SmithyModelLoader.load("models/sesv2.json");
        SmithyResponseValidator validator = new SmithyResponseValidator(model);  // JSON strict
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper json = new ObjectMapper();

        ServiceShape service = model.expectShape(SERVICE_ID, ServiceShape.class);
        // Phase 3 ordering: run Delete* last so Get / List / Put / etc. on the
        // synthetic resource still see it. Inside each bucket, alphabetical so
        // CreateX runs before the op that depends on X existing.
        List<OperationShape> ops = service.getAllOperations().stream()
                .sorted()
                .map(id -> model.expectShape(id, OperationShape.class))
                .sorted(java.util.Comparator.comparing(
                        (OperationShape o) -> o.getId().getName().startsWith("Delete"))
                        .thenComparing(o -> o.getId().getName()))
                .toList();

        CoverageReport report = new CoverageReport("SES v2 (REST JSON) — Phase 3 coverage probe");
        MinimalRequestBuilder requestBuilder = new MinimalRequestBuilder(model);

        seedV2(http);

        for (OperationShape op : ops) {
            String opName = op.getId().getName();
            HttpTrait httpTrait = op.getTrait(HttpTrait.class).orElse(null);
            if (httpTrait == null) {
                report.record(new CoverageReport.Entry(opName,
                        CoverageReport.Status.ERROR, -1, "no @http trait on operation"));
                continue;
            }
            try {
                String uri = ENDPOINT + fillPathParams(
                        httpTrait.getUri().toString(), requestBuilder.resolvePathParams(op));
                JsonNode body = requestBuilder.buildJsonBody(op);
                HttpRequest request = buildRequest(httpTrait.getMethod(), uri, body, json);
                HttpResponse<String> response = http.send(
                        request, HttpResponse.BodyHandlers.ofString());
                report.record(classify(opName, op, response, validator, json, model));
            } catch (Exception e) {
                report.record(new CoverageReport.Entry(
                        opName, CoverageReport.Status.ERROR, -1,
                        "harness exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        Path reportFile = Path.of("target", "ses-v2-coverage.md");
        report.writeTo(reportFile);

        System.out.println("=== " + report.shortSummary() + " ===");
        System.out.println("=== Full report: " + reportFile.toAbsolutePath() + " ===");

        assertThat(report.total()).isGreaterThan(0);
        assertThat(report.implemented())
                .as("at least one v2 SES op must be implemented")
                .isGreaterThan(0);
    }

    private static HttpRequest buildRequest(String method, String uri, JsonNode body,
                                             ObjectMapper json) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", SES_AUTH);
        String bodyJson = body == null ? "{}" : body.toString();
        switch (method) {
            case "GET" -> b.GET();
            case "DELETE" -> b.DELETE();
            case "POST" -> {
                b.header("Content-Type", "application/json");
                b.POST(HttpRequest.BodyPublishers.ofString(bodyJson));
            }
            case "PUT" -> {
                b.header("Content-Type", "application/json");
                b.PUT(HttpRequest.BodyPublishers.ofString(bodyJson));
            }
            default -> b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return b.build();
    }

    private static String fillPathParams(String template, java.util.Map<String, String> values) {
        Matcher m = PATH_PARAM.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String paramName = m.group(1);
            // Member names in templates may have a leading "+" for greedy labels — strip.
            if (paramName.startsWith("+")) {
                paramName = paramName.substring(1);
            }
            String value = values.getOrDefault(paramName, SYNTHETIC);
            m.appendReplacement(sb,
                    Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static CoverageReport.Entry classify(String opName, OperationShape op,
                                                  HttpResponse<String> response,
                                                  SmithyResponseValidator validator,
                                                  ObjectMapper json,
                                                  Model model) throws Exception {
        int status = response.statusCode();
        String body = response.body();

        if (status == 200) {
            ShapeId outId = op.getOutputShape();
            // SES v2 returns the output as the JSON root directly (no envelope).
            JsonNode actual = body.isBlank()
                    ? json.createObjectNode()
                    : json.readTree(body);
            var errors = validator.validate(actual, outId);
            if (errors.isEmpty()) {
                FieldCoverage.Result fc = FieldCoverage.measure(actual, outId, model, /* xmlMode= */ false);
                return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_OK,
                        status, "200, shape ok", fc);
            }
            return new CoverageReport.Entry(opName, CoverageReport.Status.IMPLEMENTED_DRIFT,
                    status, errors.size() + " shape drift(s): " + errors.get(0));
        }

        String type = extractType(body, json);
        // 404 with a recognised error type and a body referencing the synthetic
        // resource means the op is routed and just missing the requested resource —
        // that's IMPLEMENTED_STATE.
        if (status == 404) {
            if ("NotFoundException".equals(type)) {
                return new CoverageReport.Entry(opName,
                        CoverageReport.Status.IMPLEMENTED_STATE, status, type);
            }
            // Anything else at 404 (empty body, RESTEASY default, etc.) suggests no
            // JAX-RS route is registered.
            return new CoverageReport.Entry(opName,
                    CoverageReport.Status.NOT_IMPLEMENTED, status,
                    "404 (no JAX-RS route" + (type == null ? "" : ", type=" + type) + ")");
        }
        if (status == 405) {
            return new CoverageReport.Entry(opName,
                    CoverageReport.Status.NOT_IMPLEMENTED, status, "405 Method Not Allowed");
        }
        if (type != null) {
            if (STATE_ERROR_CODES.contains(type) || type.endsWith("DoesNotExist")) {
                return new CoverageReport.Entry(opName,
                        CoverageReport.Status.IMPLEMENTED_STATE, status, type);
            }
            if (VALIDATION_ERROR_CODES.contains(type) || type.startsWith("Bad")
                    || type.startsWith("Invalid")) {
                return new CoverageReport.Entry(opName,
                        CoverageReport.Status.IMPLEMENTED_VALIDATION, status, type);
            }
        }
        return new CoverageReport.Entry(opName, CoverageReport.Status.ERROR, status,
                "unclassified: type=" + type + ", status=" + status
                        + (body.isBlank() ? "" : ", body[0..80]=" + body.substring(0, Math.min(80, body.length()))));
    }

    /**
     * Phase 3 pre-seed for the v2 REST surface. Mirrors {@code seedV1}:
     *   - blind-delete any leftover {@code cov-probe-x} resource so the iteration's
     *     Create / Put op returns 200 instead of {@code AlreadyExistsException};
     *   - re-enable account-level sending so {@code SendEmail} can land in OK.
     */
    private static void seedV2(HttpClient http) throws Exception {
        String s = MinimalRequestBuilder.SYNTHETIC;
        // Best-effort cleanup
        String[] deletePaths = {
                "/v2/email/configuration-sets/" + s,
                "/v2/email/identities/" + s,
                "/v2/email/templates/" + s,
                "/v2/email/suppression/addresses/" + s,
        };
        for (String path : deletePaths) {
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create(ENDPOINT + path))
                            .header("Authorization", SES_AUTH)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        // Re-enable account sending
        http.send(HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + "/v2/email/account/sending"))
                        .header("Authorization", SES_AUTH)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"SendingEnabled\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static String extractType(String body, ObjectMapper json) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode t = root.path("__type");
            if (t.isTextual()) {
                return t.asText();
            }
            JsonNode code = root.path("Code");
            return code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
