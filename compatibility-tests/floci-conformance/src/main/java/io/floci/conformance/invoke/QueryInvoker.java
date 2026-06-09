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
import java.util.stream.Collectors;

/**
 * Invoker for the AWS Query protocol — used by SES v1, SQS, SNS, IAM, STS, etc.
 *
 * <p>Sends a form-encoded {@code application/x-www-form-urlencoded} POST to the
 * service endpoint with an {@code Action=…} param. Response is XML.
 */
public final class QueryInvoker implements Invoker {

    private static final String PROTOCOL = "aws.protocols#awsQuery";

    private final HttpClient http;
    private final String baseUrl;
    private final String actionApiVersion;

    public QueryInvoker(String baseUrl, String actionApiVersion) {
        this.baseUrl = baseUrl;
        this.actionApiVersion = actionApiVersion;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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

    /** Convenience for tests; preserves insertion order. */
    static String formEncode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }
}
