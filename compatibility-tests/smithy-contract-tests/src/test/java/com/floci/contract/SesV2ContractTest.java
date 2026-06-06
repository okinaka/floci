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
 * PoC for the Smithy contract-test harness. Validates the wire shape of one SES V2
 * REST JSON endpoint (GetConfigurationSet) against the AWS-official Smithy model.
 *
 * <p>Floci is expected to be running at {@code http://localhost:4566} for this test.
 * The test makes a raw HTTP request — no AWS SDK — so the assertion catches any
 * shape drift that the SDK's lenient parsing would otherwise hide.
 */
@DisplayName("SES V2 — Smithy contract")
class SesV2ContractTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    private static SmithyResponseValidator validator;
    private static HttpClient http;
    private static ObjectMapper json;

    @BeforeAll
    static void setUp() {
        Model model = SmithyModelLoader.load("models/sesv2.json");
        validator = new SmithyResponseValidator(model);
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        json = new ObjectMapper();
    }

    @Test
    void getConfigurationSet_responseShape_conformsToSesV2Smithy() throws Exception {
        String csName = "smithy-contract-poc";
        createConfigurationSet(csName);
        try {
            HttpResponse<String> response = sendGet("/v2/email/configuration-sets/" + csName);
            assertThat(response.statusCode())
                    .as("GET configuration-set should return 200; body: %s", response.body())
                    .isEqualTo(200);

            JsonNode actual = json.readTree(response.body());
            List<SmithyResponseValidator.ValidationError> errors = validator.validate(
                    actual,
                    ShapeId.from("com.amazonaws.sesv2#GetConfigurationSetResponse"));

            assertThat(errors)
                    .withFailMessage(() -> "Smithy contract drift for GetConfigurationSetResponse "
                            + "(see https://github.com/aws/api-models-aws/blob/main/models/sesv2/service/2019-09-27/sesv2-2019-09-27.json):\n"
                            + "  body: " + actual.toPrettyString() + "\n"
                            + "  errors:\n    " + String.join("\n    ", errors.stream().map(Object::toString).toList()))
                    .isEmpty();
        } finally {
            deleteConfigurationSet(csName);
        }
    }

    private static void createConfigurationSet(String name) throws Exception {
        HttpResponse<String> res = sendPost(
                "/v2/email/configuration-sets",
                "{\"ConfigurationSetName\":\"" + name + "\"}");
        assertThat(res.statusCode())
                .as("Pre-condition CreateConfigurationSet should succeed; body: %s", res.body())
                .isEqualTo(200);
    }

    private static void deleteConfigurationSet(String name) {
        try {
            sendDelete("/v2/email/configuration-sets/" + name);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static HttpResponse<String> sendGet(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", SES_AUTH)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendPost(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", SES_AUTH)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendDelete(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", SES_AUTH)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
