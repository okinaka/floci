package io.floci.conformance.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.conformance.model.Variant;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.traits.HttpTrait;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Invoker for the AWS REST JSON protocol — used by SES v2, Lambda, API Gateway, etc.
 *
 * <p>Uses the operation's {@code @http} trait to determine method, path template,
 * and status code. Path labels ({@code {name}}) are substituted from
 * {@link Variant#pathParams()}.
 */
public final class RestJsonInvoker implements Invoker {

    private static final String PROTOCOL = "aws.protocols#restJson1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_REGION = "us-east-1";

    /** Headers the JDK HttpClient refuses to set explicitly. */
    private static final java.util.Set<String> RESTRICTED_HEADERS = java.util.Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient http;
    private final String baseUrl;
    private final String sigV4Service;
    private final String region;

    public RestJsonInvoker(String baseUrl, String sigV4Service, String region) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public RestJsonInvoker(String baseUrl, String sigV4Service) {
        this(baseUrl, sigV4Service, DEFAULT_REGION);
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        OperationShape op = variant.operation();
        HttpTrait http = op.getTrait(HttpTrait.class).orElseThrow(() ->
                new IllegalStateException("Operation " + op.getId() + " lacks @http trait"));

        String method = http.getMethod();
        String path = resolvePath(http.getUri().toString(), variant.pathParams());
        String url = baseUrl + path + buildQueryString(variant.queryParams());

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", SigV4Stub.authorization(sigV4Service, region))
                .header("x-amz-date", SigV4Stub.AMZ_DATE);
        for (Map.Entry<String, String> e : variant.headers().entrySet()) {
            if (RESTRICTED_HEADERS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            b.header(e.getKey(), e.getValue());
        }

        byte[] bodyBytes = encodeBody(variant.jsonBody());
        if (bodyBytes.length > 0) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        } else if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));
        }

        try {
            HttpResponse<String> resp = this.http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            return new InvocationResponse(resp.statusCode(), contentType, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + op.getId(), e);
        }
    }

    private static byte[] encodeBody(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return new byte[0];
        }
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize JSON body", e);
        }
    }

    private static String resolvePath(String template, Map<String, String> labels) {
        String path = template;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        for (Map.Entry<String, String> e : labels.entrySet()) {
            path = path.replace("{" + e.getKey() + "}", URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            path = path.replace("{" + e.getKey() + "+}", e.getValue()); // greedy label, no encode
        }
        return path;
    }

    private static String buildQueryString(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
