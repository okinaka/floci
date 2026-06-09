package io.floci.conformance.synth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void name_member_falls_back_to_default() {
        assertThat(FormatHints.stringForName("PolicyName")).isEqualTo(FormatHints.DEFAULT);
        assertThat(FormatHints.stringForName("RuleSetName")).isEqualTo(FormatHints.DEFAULT);
        assertThat(FormatHints.stringForName("TemplateName")).isEqualTo(FormatHints.DEFAULT);
    }

    @Test
    void null_owner_returns_default() {
        assertThat(FormatHints.stringFor(null)).isEqualTo(FormatHints.DEFAULT);
    }
}
