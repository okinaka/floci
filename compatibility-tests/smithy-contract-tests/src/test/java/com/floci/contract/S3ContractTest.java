package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC for the Smithy contract harness against an <b>AWS REST XML</b> service (S3).
 *
 * <p>Wire form: a REST verb + path returns a single XML document whose root element
 * matches the {@code xmlName} of the operation's output shape. XmlMapper turns the
 * XML into a {@link JsonNode}, dropping the root element name (the root becomes the
 * top-level object). Wrapped lists ({@code <Buckets><Bucket>...</Bucket></Buckets>})
 * deserialize as {@code "Buckets": {"Bucket": [...]}}, which the validator's
 * {@code walkList} unwraps automatically by matching the inner key against the list
 * member's {@code xmlName} trait.
 */
@DisplayName("S3 — Smithy contract (REST XML)")
class S3ContractTest {

    private static final String ENDPOINT = System.getProperty(
            "floci.endpoint", "http://localhost:4566");
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";

    private static SmithyResponseValidator validator;
    private static HttpClient http;
    private static XmlMapper xml;

    @BeforeAll
    static void setUp() {
        Model model = SmithyModelLoader.load("models/s3.json");
        validator = new SmithyResponseValidator(model);
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        xml = new XmlMapper();
    }

    @Test
    void listBuckets_responseShape_conformsToS3Smithy() throws Exception {
        String[] names = {"floci-smithy-poc-a", "floci-smithy-poc-b"};
        for (String n : names) {
            createBucket(n);
        }
        try {
            HttpResponse<String> response = sendGet("/");
            assertThat(response.statusCode())
                    .as("ListBuckets should return 200; body: %s", response.body())
                    .isEqualTo(200);

            JsonNode actual = xml.readTree(response.body().getBytes(StandardCharsets.UTF_8));
            List<SmithyResponseValidator.ValidationError> errors = validator.validate(
                    actual,
                    ShapeId.from("com.amazonaws.s3#ListBucketsOutput"));

            assertThat(errors)
                    .withFailMessage(() -> "Smithy contract drift for ListBucketsOutput "
                            + "(see https://github.com/aws/api-models-aws/blob/main/models/s3/service/2006-03-01/s3-2006-03-01.json):\n"
                            + "  body: " + actual.toPrettyString() + "\n"
                            + "  errors:\n    "
                            + String.join("\n    ", errors.stream().map(Object::toString).toList()))
                    .isEmpty();
        } finally {
            for (String n : names) {
                deleteBucket(n);
            }
        }
    }

    private static void createBucket(String name) throws Exception {
        HttpResponse<String> res = sendPut("/" + name, "");
        assertThat(res.statusCode())
                .as("Pre-condition CreateBucket should succeed; body: %s", res.body())
                .isIn(200, 409);  // 409 = already exists from a prior run
    }

    private static void deleteBucket(String name) {
        try {
            sendDelete("/" + name);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static HttpResponse<String> sendGet(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", S3_AUTH)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendPut(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", S3_AUTH)
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendDelete(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + path))
                        .header("Authorization", S3_AUTH)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
