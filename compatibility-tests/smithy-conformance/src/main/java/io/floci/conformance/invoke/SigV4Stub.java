package io.floci.conformance.invoke;

/**
 * Emits a stub SigV4 {@code Authorization} header carrying just enough
 * structure for AWS emulators to extract the service name and route requests.
 * The signature itself is a constant {@code 00}; emulators we target
 * (Floci, fakecloud, LocalStack, ministack) do not validate the signature.
 *
 * <p>The Credential scope's <em>service</em> segment is what fakecloud, in
 * particular, uses to disambiguate path-overlapping endpoints (e.g.
 * {@code /v2/email/...} is SES v2 only when the Authorization names
 * {@code ses}, otherwise it falls through to the ECR /v2/ registry router).
 *
 * <p>Date stamps are constants so reports and baselines stay byte-identical
 * across runs.
 */
public final class SigV4Stub {

    public static final String DATE = "20260101";
    public static final String AMZ_DATE = DATE + "T000000Z";
    private static final String ACCESS_KEY = "test";
    private static final String SIGNATURE = "00";

    private SigV4Stub() {
    }

    /**
     * Build the {@code Authorization} header value for the given AWS SigV4
     * service name (e.g. {@code ses}) and region.
     */
    public static String authorization(String service, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/" + DATE + "/" + region
                + "/" + service + "/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=" + SIGNATURE;
    }
}
