package io.floci.conformance.synth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link FormatHints}: member-name → synthetic-value
 * heuristics (email, domain, service-specific ARNs, URLs) and the default
 * placeholder fallback.
 */
class FormatHintsTest {

    @Test
    void email_like_members_get_email() {
        assertThat(FormatHints.stringForName("EmailAddress")).contains("@");
        assertThat(FormatHints.stringForName("Source")).contains("@");
        assertThat(FormatHints.stringForName("FromEmailAddress")).contains("@");
        assertThat(FormatHints.stringForName("ReplyToAddresses")).contains("@");
    }

    @Test
    void identity_resolves_to_email() {
        // SES "Identity" accepts both email and domain; we lean on email so it
        // matches Source-like fields in the same op.
        assertThat(FormatHints.stringForName("Identity")).contains("@");
        assertThat(FormatHints.stringForName("EmailIdentity")).contains("@");
        assertThat(FormatHints.stringForName("Identities")).contains("@");
    }

    @Test
    void domain_members_get_domain() {
        assertThat(FormatHints.stringForName("MailFromDomain"))
                .matches(".+\\..+");
        assertThat(FormatHints.stringForName("Domain"))
                .matches(".+\\..+");
    }

    @Test
    void arn_members_get_arn_by_service() {
        assertThat(FormatHints.stringForName("TopicArn")).startsWith("arn:aws:sns:");
        assertThat(FormatHints.stringForName("RoleArn")).startsWith("arn:aws:iam:");
        assertThat(FormatHints.stringForName("DeliveryStreamArn")).startsWith("arn:aws:firehose:");
        assertThat(FormatHints.stringForName("KinesisStreamArn")).startsWith("arn:aws:kinesis:");
        // Generic *Arn falls back to SES.
        assertThat(FormatHints.stringForName("ResourceArn")).startsWith("arn:aws:ses:");
    }

    @Test
    void url_members_get_url() {
        assertThat(FormatHints.stringForName("CallbackUrl")).startsWith("https://");
        assertThat(FormatHints.stringForName("ConfigurationSetEventDestinationEndpoint"))
                .startsWith("https://");
    }

    @Test
    void content_type_members_get_valid_media_type() {
        // Must parse as type/subtype or strict servers (RESTEasy) reject the
        // request with a 400 before the endpoint runs.
        assertThat(FormatHints.stringForName("ContentType")).matches("[^/]+/[^/]+");
        assertThat(FormatHints.stringForName("ResponseContentType")).matches("[^/]+/[^/]+");
        assertThat(FormatHints.stringForName("AttachmentContentType")).matches("[^/]+/[^/]+");
        assertThat(FormatHints.stringForName("MediaType")).matches("[^/]+/[^/]+");
    }

    @Test
    void part_number_marker_gets_numeric_value() {
        // Modeled as a plain string but must be an integer on the wire.
        assertThat(FormatHints.stringForName("PartNumberMarker")).matches("\\d+");
    }

    @Test
    void name_member_falls_back_to_default() {
        assertThat(FormatHints.stringForName("PolicyName")).isEqualTo(FormatHints.DEFAULT);
        assertThat(FormatHints.stringForName("RuleSetName")).isEqualTo(FormatHints.DEFAULT);
        assertThat(FormatHints.stringForName("TemplateName")).isEqualTo(FormatHints.DEFAULT);
    }

    @Test
    void null_owner_returns_default() {
        assertThat(FormatHints.stringFor(null)).isEqualTo(FormatHints.DEFAULT);
    }

    @Test
    void tagging_gets_query_string_form() {
        assertThat(FormatHints.stringForName("Tagging")).contains("=");
    }

    @Test
    void sse_c_members_get_a_mutually_consistent_triple() throws Exception {
        assertThat(FormatHints.stringForName("SSECustomerAlgorithm")).isEqualTo("AES256");
        assertThat(FormatHints.stringForName("CopySourceSSECustomerAlgorithm")).isEqualTo("AES256");

        byte[] key = java.util.Base64.getDecoder()
                .decode(FormatHints.stringForName("SSECustomerKey"));
        assertThat(key).hasSize(32);

        byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(key);
        assertThat(FormatHints.stringForName("SSECustomerKeyMD5"))
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(md5));
        assertThat(FormatHints.stringForName("CopySourceSSECustomerKeyMD5"))
                .isEqualTo(FormatHints.stringForName("SSECustomerKeyMD5"));
        assertThat(FormatHints.stringForName("CopySourceSSECustomerKey"))
                .isEqualTo(FormatHints.stringForName("SSECustomerKey"));
    }
}
