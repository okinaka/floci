package io.floci.conformance.synth;

import software.amazon.smithy.model.shapes.MemberShape;

/**
 * Picks plausible synthetic string values from a member's name. The default
 * "cov-probe-x" placeholder fails AWS validation for anything that expects an
 * email address, ARN, URL, domain, or similar formatted string — these
 * heuristics generate values that pass the common format / pattern checks so
 * the harness can reach the operation's real logic instead of stopping at
 * input validation.
 *
 * <p>The hints are name-based only. Honouring {@code @pattern} fully would
 * require ECMA-regex generation, which is out of scope for this layer; the
 * common AWS member-name conventions cover most input formats in practice.
 *
 * <p>All synthesized values share the {@code cov-probe} prefix so they're easy
 * to spot in logs, reports, and emulator state inspections.
 */
public final class FormatHints {

    /** Default placeholder when no specific format hint applies. */
    public static final String DEFAULT = "cov-probe-x";

    private static final String EMAIL = "cov-probe@example.com";
    private static final String DOMAIN = "cov-probe.example.com";
    private static final String ARN_SES = "arn:aws:ses:us-east-1:123456789012:identity/cov-probe";
    private static final String ARN_SNS_TOPIC = "arn:aws:sns:us-east-1:123456789012:cov-probe-topic";
    private static final String ARN_IAM_ROLE = "arn:aws:iam::123456789012:role/cov-probe-role";
    private static final String ARN_S3_BUCKET = "arn:aws:s3:::cov-probe-bucket";
    private static final String ARN_KINESIS = "arn:aws:kinesis:us-east-1:123456789012:stream/cov-probe";
    private static final String ARN_FIREHOSE = "arn:aws:firehose:us-east-1:123456789012:deliverystream/cov-probe";
    private static final String URL = "https://example.com/cov-probe";
    private static final String S3_BUCKET = "cov-probe-bucket";
    private static final String MEDIA_TYPE = "application/cov-probe";
    private static final String NUMERIC_MARKER = "1";
    // S3 SSE-C: servers validate the triple as a set — algorithm must be
    // AES256, the key must be base64 of exactly 32 bytes, and the MD5 must be
    // base64(MD5(key bytes)). All three derive from the fixed key
    // "cov-probe-sse-c-0123456789abcdef" so every op sends the same matching
    // set and read-after-write cases can unlock what an earlier write stored.
    private static final String SSE_C_ALGORITHM = "AES256";
    private static final String SSE_C_KEY = "Y292LXByb2JlLXNzZS1jLTAxMjM0NTY3ODlhYmNkZWY=";
    private static final String SSE_C_KEY_MD5 = "GpzWiFdbVZMV6RGLbbJL9A==";
    // S3's x-amz-tagging header is a URL query string; a bare token is rejected
    // as "missing '=' in pair".
    private static final String TAGGING = "cov-probe=x";

    private FormatHints() {
    }

    /**
     * @return a format-typed synthetic value if the member's name matches a
     *         known hint, otherwise {@link #DEFAULT}.
     */
    public static String stringFor(MemberShape owner) {
        if (owner == null) {
            return DEFAULT;
        }
        return stringForName(owner.getMemberName());
    }

    /** Visible for unit tests. */
    static String stringForName(String memberName) {
        if (memberName == null) {
            return DEFAULT;
        }
        String n = memberName;
        String lower = n.toLowerCase();

        // ARNs: most specific service families first so generic *Arn doesn't win.
        if (containsAll(lower, "topic", "arn") || endsWithIgnoreCase(n, "TopicArn")) {
            return ARN_SNS_TOPIC;
        }
        if (containsAll(lower, "role", "arn") || endsWithIgnoreCase(n, "RoleArn")) {
            return ARN_IAM_ROLE;
        }
        if (containsAll(lower, "bucket", "arn")) {
            return ARN_S3_BUCKET;
        }
        // Firehose first — "DeliveryStreamArn" contains both "delivery" and
        // "stream", and Firehose is more specific than bare Kinesis.
        if (containsAll(lower, "firehose", "arn") || containsAll(lower, "delivery", "arn")) {
            return ARN_FIREHOSE;
        }
        if (containsAll(lower, "kinesis", "arn") || containsAll(lower, "stream", "arn")) {
            return ARN_KINESIS;
        }
        if (endsWithIgnoreCase(n, "Arn") || endsWithIgnoreCase(n, "ARN")) {
            return ARN_SES;
        }

        // Email-like
        if (containsAny(lower, "emailaddress", "fromemail", "returnpath", "feedbackforwarding")
                || equalsIgnoreCase(n, "Source")
                || equalsIgnoreCase(n, "ReplyToAddresses")) {
            return EMAIL;
        }

        // Domain-like. SES "Identity" is either email or domain — pick email so
        // `Identity == Source` style ops stay self-consistent; the few
        // domain-only ops (PutIdentity*MailFrom) name the field explicitly.
        if (containsAny(lower, "maildomain", "mailfromdomain", "domain")
                || endsWithIgnoreCase(n, "Domain")) {
            return DOMAIN;
        }

        // URLs and HTTPS endpoints.
        if (containsAny(lower, "url", "endpoint", "callback") && !lower.endsWith("name")) {
            return URL;
        }

        // S3 bucket-ish (without arn) — fall back to bare name.
        if (containsAny(lower, "bucketname") || equalsIgnoreCase(n, "Bucket")) {
            return S3_BUCKET;
        }

        // Identity / EmailIdentity ambiguous; lean on Email format.
        if (equalsIgnoreCase(n, "EmailIdentity") || equalsIgnoreCase(n, "Identity")
                || equalsIgnoreCase(n, "Identities")) {
            return EMAIL;
        }

        // Content-Type / media-type headers must parse as type/subtype or strict
        // servers (RESTEasy) reject them before the endpoint runs. Keep the
        // cov-probe marker inside a valid media type.
        if (containsAny(lower, "contenttype") || equalsIgnoreCase(n, "MediaType")) {
            return MEDIA_TYPE;
        }

        // PartNumberMarker is modeled as a plain string but is an integer
        // pagination marker on the wire; a non-numeric value is rejected before
        // the endpoint runs.
        if (containsAny(lower, "partnumbermarker")) {
            return NUMERIC_MARKER;
        }

        // S3 x-amz-tagging: URL-encoded key=value pairs.
        if (equalsIgnoreCase(n, "Tagging")) {
            return TAGGING;
        }

        // S3 SSE-C headers ([CopySource]SSECustomerAlgorithm/Key/KeyMD5).
        if (lower.contains("ssecustomer")) {
            if (lower.contains("algorithm")) {
                return SSE_C_ALGORITHM;
            }
            if (lower.endsWith("md5")) {
                return SSE_C_KEY_MD5;
            }
            return SSE_C_KEY;
        }

        return DEFAULT;
    }

    private static boolean containsAll(String s, String... parts) {
        for (String p : parts) {
            if (!s.contains(p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String s, String... parts) {
        for (String p : parts) {
            if (s.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        return s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length());
    }
}
