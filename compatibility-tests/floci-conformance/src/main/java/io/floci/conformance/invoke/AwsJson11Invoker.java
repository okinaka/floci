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
 * Invoker for the AWS JSON 1.1 protocol — SSM, KMS, Secrets Manager,
 * EventBridge, etc. Every operation is a POST to the service root; the
 * operation is selected by the {@code X-Amz-Target} header
 * ({@code <targetPrefix>.<OperationName>}) and the input rides as an
 * {@code application/x-amz-json-1.1} body.
 */
public final class AwsJson11Invoker implements Invoker {

    private static final String PROTOCOL = "aws.protocols#awsJson1_1";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String targetPrefix;
    private final String sigV4Service;
    private final String region;

    public AwsJson11Invoker(String baseUrl, String targetPrefix,
                            String sigV4Service, String region) {
        this.baseUrl = baseUrl;
        this.targetPrefix = targetPrefix;
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public AwsJson11Invoker(String baseUrl, String targetPrefix, String sigV4Service) {
        this(baseUrl, targetPrefix, sigV4Service, DEFAULT_REGION);
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        String body = variant.jsonBody() == null
                ? "{}"
                : MAPPER.writeValueAsString(variant.jsonBody());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", CONTENT_TYPE)
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
