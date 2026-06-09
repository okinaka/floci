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
