package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC for the Smithy contract harness against an <b>AWS JSON 1.1</b> service (SSM).
 *
 * <p>The wire shape differs from REST JSON (used by SES v2) and Query/XML (SES v1)
 * in dispatch only — JSON 1.1 uses an {@code X-Amz-Target} header instead of a URL
 * path, and a {@code application/x-amz-json-1.1} content type — but the response
 * body is plain JSON, so the same {@link SmithyResponseValidator} walks it without
 * any extra adapter layer.
 *
 * <p>This PoC chooses SSM {@code GetParameter} because the shape is small and
 * representative of the broader JSON 1.1 family (~10 services including KMS,
 * EventBridge, Secrets Manager, Cognito, Kinesis, CloudWatch Logs).
 */
@DisplayName("SSM — Smithy contract (AWS JSON 1.1)")
class SsmContractTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ssm/aws4_request";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    private static SmithyResponseValidator validator;
    private static HttpClient http;
    private static ObjectMapper json;

    @BeforeAll
    static void setUp() {
        Model model = SmithyModelLoader.load("models/ssm.json");
        validator = new SmithyResponseValidator(model);
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        json = new ObjectMapper();
    }

    @Test
    void getParameter_responseShape_conformsToSsmSmithy() throws Exception {
        String name = "/floci/smithy-contract-poc";
        putParameter(name, "hello-smithy");
        try {
            HttpResponse<String> response = sendJsonRpc(
                    "AmazonSSM.GetParameter",
                    "{\"Name\":\"" + name + "\"}");
            assertThat(response.statusCode())
                    .as("GetParameter should return 200; body: %s", response.body())
                    .isEqualTo(200);

            JsonNode actual = json.readTree(response.body());
            List<SmithyResponseValidator.ValidationError> errors = validator.validate(
                    actual,
                    ShapeId.from("com.amazonaws.ssm#GetParameterResult"));

            assertThat(errors)
                    .withFailMessage(() -> "Smithy contract drift for GetParameterResult "
                            + "(see https://github.com/aws/api-models-aws/blob/main/models/ssm/service/2014-11-06/ssm-2014-11-06.json):\n"
                            + "  body: " + actual.toPrettyString() + "\n"
                            + "  errors:\n    "
                            + String.join("\n    ", errors.stream().map(Object::toString).toList()))
                    .isEmpty();
        } finally {
            deleteParameter(name);
        }
    }

    private static void putParameter(String name, String value) throws Exception {
        HttpResponse<String> res = sendJsonRpc(
                "AmazonSSM.PutParameter",
                "{\"Name\":\"" + name + "\",\"Value\":\"" + value + "\",\"Type\":\"String\","
                        + "\"Overwrite\":true}");
        assertThat(res.statusCode())
                .as("Pre-condition PutParameter should succeed; body: %s", res.body())
                .isEqualTo(200);
    }

    private static void deleteParameter(String name) {
        try {
            sendJsonRpc("AmazonSSM.DeleteParameter", "{\"Name\":\"" + name + "\"}");
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static HttpResponse<String> sendJsonRpc(String target, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + "/"))
                        .header("Authorization", SSM_AUTH)
                        .header("Content-Type", JSON_1_1)
                        .header("X-Amz-Target", target)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
