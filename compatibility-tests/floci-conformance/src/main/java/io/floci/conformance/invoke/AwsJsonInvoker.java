package io.floci.conformance.invoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.conformance.model.Variant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Invoker for the AWS JSON protocol family (1.0 and 1.1) — DynamoDB and
 * Step Functions on 1.0; SSM, KMS, Secrets Manager, EventBridge on 1.1.
 * Every operation is a POST to the service root; the operation is selected
 * by the {@code X-Amz-Target} header ({@code <targetPrefix>.<OperationName>})
 * and the input rides as the version-specific
 * {@code application/x-amz-json-1.x} body. The two minor versions differ
 * only in that content type.
 */
public final class AwsJsonInvoker implements Invoker {

    /** Protocol/content-type pairing for one awsJson minor version. */
    public enum Flavor {
        AWS_JSON_1_0("aws.protocols#awsJson1_0", "application/x-amz-json-1.0"),
        AWS_JSON_1_1("aws.protocols#awsJson1_1", "application/x-amz-json-1.1");

        final String protocol;
        final String contentType;

        Flavor(String protocol, String contentType) {
            this.protocol = protocol;
            this.contentType = contentType;
        }
    }

    private static final String DEFAULT_REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String targetPrefix;
    private final String sigV4Service;
    private final String region;
    private final Flavor flavor;

    public AwsJsonInvoker(String baseUrl, String targetPrefix,
                          String sigV4Service, String region, Flavor flavor) {
        this.baseUrl = baseUrl;
        this.targetPrefix = targetPrefix;
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.flavor = flavor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public AwsJsonInvoker(String baseUrl, String targetPrefix, String sigV4Service,
                          Flavor flavor) {
        this(baseUrl, targetPrefix, sigV4Service, DEFAULT_REGION, flavor);
    }

    @Override
    public String protocol() {
        return flavor.protocol;
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        String body = variant.jsonBody() == null
                ? "{}"
                : MAPPER.writeValueAsString(variant.jsonBody());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", flavor.contentType)
                .header("X-Amz-Target", targetPrefix + "." + variant.operationName())
                .header("Authorization", SigV4Stub.authorization(sigV4Service, region))
                .header("x-amz-date", SigV4Stub.AMZ_DATE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            return new InvocationResponse(resp.statusCode(), contentType, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + variant.operationName(), e);
        }
    }
}
