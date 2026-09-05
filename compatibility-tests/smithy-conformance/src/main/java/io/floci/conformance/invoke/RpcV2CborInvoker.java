package io.floci.conformance.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.floci.conformance.model.Variant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Invoker for the Smithy RPC v2 CBOR protocol ({@code smithy.protocols#rpcv2Cbor}).
 * Every operation is a {@code POST /service/{service}/operation/{operation}} with
 * a CBOR body and the {@code Smithy-Protocol: rpc-v2-cbor} header (the non-payload
 * signal a server claims the protocol from). The CBOR response is decoded back to
 * JSON so the shared {@code ShapeValidator} / {@code ErrorClassifier} (which work
 * on JSON text) need no CBOR awareness.
 *
 * <p>{@code service} is the Smithy service shape's local name — for CloudWatch
 * that is {@code GraniteServiceVersion20100801}, which is also the id Floci's CBOR
 * dispatcher matches on.
 */
public final class RpcV2CborInvoker implements Invoker {

    private static final String DEFAULT_REGION = "us-east-1";
    private static final String CBOR_MEDIA_TYPE = "application/cbor";
    private static final CBORMapper CBOR = new CBORMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String service;
    private final String sigV4Service;
    private final String region;

    public RpcV2CborInvoker(String baseUrl, String service, String sigV4Service, String region) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.service = service;
        this.sigV4Service = sigV4Service;
        this.region = region;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public RpcV2CborInvoker(String baseUrl, String service, String sigV4Service) {
        this(baseUrl, service, sigV4Service, DEFAULT_REGION);
    }

    @Override
    public String protocol() {
        return "smithy.protocols#rpcv2Cbor";
    }

    @Override
    public InvocationResponse send(Variant variant) throws IOException {
        JsonNode bodyNode = variant.jsonBody() == null
                ? JSON.createObjectNode()
                : variant.jsonBody();
        byte[] cborBody = CBOR.writeValueAsBytes(bodyNode);

        String url = baseUrl + "/service/" + service + "/operation/" + variant.operationName();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Smithy-Protocol", "rpc-v2-cbor")
                .header("Content-Type", CBOR_MEDIA_TYPE)
                .header("Accept", CBOR_MEDIA_TYPE)
                .header("Authorization", SigV4Stub.authorization(sigV4Service, region))
                .header("x-amz-date", SigV4Stub.AMZ_DATE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cborBody))
                .build();

        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            return new InvocationResponse(resp.statusCode(), contentType, decodeToJson(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + variant.operationName(), e);
        }
    }

    /**
     * Decode a CBOR response to a JSON string so downstream validation stays
     * protocol-agnostic. A body that isn't valid CBOR (e.g. an HTML 404 from an
     * unrouted request) is returned as latin1 text so it surfaces as an
     * unrouted/wrong-shape response rather than a harness error.
     */
    private static String decodeToJson(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode tree = CBOR.readTree(body);
            return JSON.writeValueAsString(tree);
        } catch (IOException notCbor) {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }
}
