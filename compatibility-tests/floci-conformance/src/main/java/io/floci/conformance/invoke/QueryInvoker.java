package io.floci.conformance.invoke;

import io.floci.conformance.model.Variant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Invoker for the AWS Query protocol — used by SES v1, SQS, SNS, IAM, STS, etc.
 *
 * <p>Sends a form-encoded {@code application/x-www-form-urlencoded} POST to the
 * service endpoint with an {@code Action=…} param. Response is XML.
 */
public final class QueryInvoker implements Invoker {

    private static final String PROTOCOL = "aws.protocols#awsQuery";

    private static final String DEFAULT_REGION = "us-east-1";

    private final HttpClient http;
    private final String baseUrl;
    private final String actionApiVersion;
    private final String sigV4Service;
    private final String region;

    public QueryInvoker(String baseUrl, String actionApiVersion,
                        String sigV4Service, String region) {
        this.baseUrl = baseUrl;
        this.actionApiVersion = actionApiVersion;
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public QueryInvoker(String baseUrl, String actionApiVersion, String sigV4Service) {
        this(baseUrl, actionApiVersion, sigV4Service, DEFAULT_REGION);
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        StringBuilder body = new StringBuilder();
        append(body, "Action", variant.operationName());
        append(body, "Version", actionApiVersion);
        for (Map.Entry<String, String> e : variant.queryParams().entrySet()) {
            append(body, e.getKey(), e.getValue());
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/xml")
                .header("Authorization", SigV4Stub.authorization(sigV4Service, region))
                .header("x-amz-date", SigV4Stub.AMZ_DATE)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            return new InvocationResponse(resp.statusCode(), contentType, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + variant.operationName(), e);
        }
    }

    private static void append(StringBuilder body, String key, String value) {
        if (body.length() > 0) {
            body.append('&');
        }
        body.append(urlEncode(key)).append('=').append(urlEncode(value));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
