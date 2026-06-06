package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC for the Smithy contract harness against SES <b>v1</b> (Query / XML protocol).
 *
 * <p>The AWS Query response is wrapped in
 * {@code <ActionResponse><ActionResult>...</ActionResult><ResponseMetadata>...</ResponseMetadata></ActionResponse>}.
 * The Smithy {@code output} shape describes the contents of the inner Result element,
 * so this test:
 * <ol>
 *   <li>Issues a Query call (form-encoded POST with {@code Action=...})</li>
 *   <li>Parses the XML body with {@link XmlMapper} into a {@link JsonNode}</li>
 *   <li>Drills into the {@code <ActionResult>} subtree</li>
 *   <li>Validates that subtree against the Smithy output shape via
 *       {@link SmithyResponseValidator}</li>
 * </ol>
 *
 * <p>The JSON-shape walker can't perfectly capture every Query/XML wire detail
 * (e.g. {@code xmlFlattened} list semantics, attribute-vs-element distinctions),
 * but for "does the response carry the declared members with the right scalar
 * types?" it's a strong drift detector.
 */
@DisplayName("SES V1 — Smithy contract (Query/XML)")
class SesV1ContractTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    private static SmithyResponseValidator validator;
    private static HttpClient http;
    private static XmlMapper xml;

    @BeforeAll
    static void setUp() {
        Model model = SmithyModelLoader.load("models/ses.json");
        validator = new SmithyResponseValidator(model);
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        xml = new XmlMapper();
    }

    @Test
    void describeConfigurationSet_responseShape_conformsToSesV1Smithy() throws Exception {
        String csName = "smithy-contract-poc-v1";
        createV1ConfigurationSet(csName);
        try {
            HttpResponse<String> response = sendQuery(
                    "Action=DescribeConfigurationSet"
                            + "&ConfigurationSetName=" + url(csName));
            assertThat(response.statusCode())
                    .as("DescribeConfigurationSet should return 200; body: %s", response.body())
                    .isEqualTo(200);

            // The Query envelope is <ActionResponse><ActionResult>{members}</ActionResult>
            // <ResponseMetadata>...</ResponseMetadata></ActionResponse>. The Smithy output
            // shape describes the inner Result.
            JsonNode envelope = xml.readTree(response.body().getBytes(StandardCharsets.UTF_8));
            JsonNode result = envelope.get("DescribeConfigurationSetResult");
            assertThat(result)
                    .as("envelope should contain DescribeConfigurationSetResult; body: %s",
                            response.body())
                    .isNotNull();

            List<SmithyResponseValidator.ValidationError> errors = validator.validate(
                    result,
                    ShapeId.from("com.amazonaws.ses#DescribeConfigurationSetResponse"));

            assertThat(errors)
                    .withFailMessage(() -> "Smithy contract drift for v1 "
                            + "DescribeConfigurationSetResponse:\n"
                            + "  inner result: " + result.toPrettyString() + "\n"
                            + "  errors:\n    "
                            + String.join("\n    ", errors.stream().map(Object::toString).toList()))
                    .isEmpty();
        } finally {
            deleteV1ConfigurationSet(csName);
        }
    }

    private static void createV1ConfigurationSet(String name) throws Exception {
        HttpResponse<String> res = sendQuery(
                "Action=CreateConfigurationSet"
                        + "&ConfigurationSet.Name=" + url(name));
        assertThat(res.statusCode())
                .as("Pre-condition CreateConfigurationSet should succeed; body: %s", res.body())
                .isEqualTo(200);
    }

    private static void deleteV1ConfigurationSet(String name) {
        try {
            sendQuery("Action=DeleteConfigurationSet&ConfigurationSetName=" + url(name));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static HttpResponse<String> sendQuery(String formBody) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + "/"))
                        .header("Authorization", SES_AUTH)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
