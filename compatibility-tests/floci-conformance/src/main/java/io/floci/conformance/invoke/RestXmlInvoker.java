package io.floci.conformance.invoke;

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
 * Invoker for the AWS REST XML protocol — S3, Route 53, CloudFront. Resolves
 * the operation's {@code @http} method and path template like the REST JSON
 * invoker, but sends the encoder's pre-serialized {@code rawBody} (XML
 * documents or raw payload bytes) with its accompanying content type.
 */
public final class RestXmlInvoker implements Invoker {

    private static final String PROTOCOL = "aws.protocols#restXml";
    private static final String DEFAULT_REGION = "us-east-1";

    /** Headers the JDK HttpClient refuses to set explicitly. */
    private static final java.util.Set<String> RESTRICTED_HEADERS = java.util.Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient http;
    private final String baseUrl;
    private final String sigV4Service;
    private final String region;

    public RestXmlInvoker(String baseUrl, String sigV4Service, String region) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public RestXmlInvoker(String baseUrl, String sigV4Service) {
        this(baseUrl, sigV4Service, DEFAULT_REGION);
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        OperationShape op = variant.operation();
        HttpTrait httpTrait = op.getTrait(HttpTrait.class).orElseThrow(() ->
                new IllegalStateException("Operation " + op.getId() + " lacks @http trait"));

        String method = httpTrait.getMethod();
        String path = resolvePath(httpTrait.getUri().toString(), variant.pathParams());
        String url = baseUrl + path + buildQueryString(variant.queryParams());

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/xml")
                .header("Authorization", SigV4Stub.authorization(sigV4Service, region))
                .header("x-amz-date", SigV4Stub.AMZ_DATE);
        for (Map.Entry<String, String> e : variant.headers().entrySet()) {
            if (RESTRICTED_HEADERS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            b.header(e.getKey(), e.getValue());
        }

        if (variant.rawBody() != null && !variant.rawBody().isEmpty()) {
            if (variant.rawContentType() != null) {
                b.header("Content-Type", variant.rawContentType());
            }
            b.method(method, HttpRequest.BodyPublishers.ofString(
                    variant.rawBody(), StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
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

    private static String resolvePath(String template, Map<String, String> labels) {
        String path = template;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        for (Map.Entry<String, String> e : labels.entrySet()) {
            path = path.replace("{" + e.getKey() + "+}", e.getValue()); // greedy, no encode
            path = path.replace("{" + e.getKey() + "}",
                    URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
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
}
