package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.floci.aws.services.ses.SesV2Requests.ENTRY;
import static io.floci.aws.services.ses.SesV2Requests.TO;
import static io.floci.aws.services.ses.SesV2Requests.post;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Without a tenant, the v2 sends look a stored template up last: an unknown configuration set is
 * reported first, then an unverified sender or feedback forwarding address, and only then an
 * unknown template (probe-confirmed). A SendBulkEmail in which no entry names a recipient answers
 * every entry before any of them, an unknown configuration set included, and a template send
 * without its template data is refused ahead of all of these (probe-confirmed; on SendEmail only
 * against the sender check, the rest follows SendBulkEmail). The v1 SendTemplatedEmail and
 * SendBulkTemplatedEmail follow the same order with Source and ReturnPath, and the bulk send
 * counts its destinations first (probe-confirmed).
 */
@QuarkusTest
class SesSendTemplateLookupOrderIntegrationTest {

    private static final String UNVERIFIED = "sender@unverified-template-order.floci.test";
    private static final String VERIFIED = "sender@template-order.floci.test";
    private static final String GHOST_CONFIG_SET = "template-order-ghost-config-set";
    private static final String GHOST_TEMPLATE =
            "{\"Template\": {\"TemplateName\": \"template-order-ghost\", \"TemplateData\": \"{}\"}}";
    private static final String INLINE_TEMPLATE =
            "{\"Template\": {\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"{}\"}}";
    private static final String NOT_VERIFIED_MESSAGE =
            "Email address is not verified. The following identities failed the check in region US-EAST-1: ";
    private static final String V1_GHOST_TEMPLATE = "template-order-v1-ghost";
    private static final String GHOST_CONFIG_SET_MEMBER = "\"ConfigurationSetName\": \"" + GHOST_CONFIG_SET + "\", ";
    private static final String NO_RECIPIENT_ENTRIES =
            "{\"Destination\": {}}, {\"Destination\": {\"ToAddresses\": []}}";

    // Not @BeforeAll: the Quarkus test port is only bound once the first test starts.
    @BeforeEach
    void verifySender() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", VERIFIED)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse bulk(String sender, String content, String members, String entries) {
        return post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + sender + "\", " + members
                + "\"DefaultContent\": " + content + ", \"BulkEmailEntries\": [" + entries + "]}");
    }

    private static ValidatableResponse send(String sender, String members) {
        return post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + sender + "\", " + members
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"Content\": " + GHOST_TEMPLATE + "}");
    }

    private static ValidatableResponse templated(String source, String... params) {
        return v1(given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", source)
            .formParam("Destination.ToAddresses.member.1", TO)
            .formParam("Template", V1_GHOST_TEMPLATE)
            .formParam("TemplateData", "{}"), params);
    }

    private static ValidatableResponse bulkTemplated(String source, int destinations, String... params) {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", source)
            .formParam("Template", V1_GHOST_TEMPLATE)
            .formParam("DefaultTemplateData", "{}");
        for (int i = 1; i <= destinations; i++) {
            spec = spec.formParam("Destinations.member." + i + ".Destination.ToAddresses.member.1", TO);
        }
        return v1(spec, params);
    }

    private static ValidatableResponse v1(RequestSpecification spec, String... params) {
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.formParam(params[i], params[i + 1]);
        }
        return spec.when().post("/").then();
    }

    private static void assertV1Error(ValidatableResponse response, String code, String message) {
        response
            .statusCode(400)
            .body(containsString("<Code>" + code + "</Code>"))
            .body(containsString("<Message>" + message + "</Message>"));
    }

    private static void assertNotVerified(ValidatableResponse response, String unverified) {
        response
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", equalTo(NOT_VERIFIED_MESSAGE + unverified));
    }

    private static void assertConfigSetNotFound(ValidatableResponse response) {
        response
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Configuration set <" + GHOST_CONFIG_SET + "> does not exist."));
    }

    private static void assertBadRequest(ValidatableResponse response, String message) {
        response
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    private static void assertEveryEntryEmpty(ValidatableResponse response) {
        response
            .statusCode(200)
            .body("BulkEmailEntryResults", hasSize(2))
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo("Destination cannot be empty"))
            .body("BulkEmailEntryResults[0].MessageId", nullValue())
            .body("BulkEmailEntryResults[1].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[1].Error", equalTo("Destination cannot be empty"));
    }

    private static void assertTemplateNotFound(ValidatableResponse response) {
        response
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Template template-order-ghost does not exist."));
    }

    @Test
    void sendBulkEmail_unverifiedSenderPrecedesUnknownTemplate() {
        assertNotVerified(bulk(UNVERIFIED, GHOST_TEMPLATE, "", ENTRY), UNVERIFIED);
    }

    @Test
    void sendBulkEmail_unverifiedSenderPrecedesUnknownTemplateArn() {
        assertNotVerified(bulk(UNVERIFIED, "{\"Template\": {\"TemplateArn\": "
                + "\"arn:aws:ses:us-east-1:000000000000:template/template-order-ghost\", \"TemplateData\": \"{}\"}}",
                "", ENTRY), UNVERIFIED);
    }

    @Test
    void sendBulkEmail_unknownConfigurationSetPrecedesUnverifiedSender() {
        assertConfigSetNotFound(bulk(UNVERIFIED, INLINE_TEMPLATE,
                "\"ConfigurationSetName\": \"" + GHOST_CONFIG_SET + "\", ", ENTRY));
    }

    @Test
    void sendBulkEmail_verifiedSender_unknownTemplate() {
        assertTemplateNotFound(bulk(VERIFIED, GHOST_TEMPLATE, "", ENTRY));
    }

    @Test
    void sendBulkEmail_unknownConfigurationSetPrecedesUnknownTemplate() {
        assertConfigSetNotFound(bulk(VERIFIED, GHOST_TEMPLATE,
                "\"ConfigurationSetName\": \"" + GHOST_CONFIG_SET + "\", ", ENTRY));
    }

    @Test
    void sendBulkEmail_unverifiedFeedbackForwardingAddressPrecedesUnknownTemplate() {
        assertNotVerified(bulk(VERIFIED, GHOST_TEMPLATE,
                "\"FeedbackForwardingEmailAddress\": \"" + UNVERIFIED + "\", ", ENTRY), UNVERIFIED);
    }

    @Test
    void sendBulkEmail_unknownTemplatePrecedesMalformedEntryAndReplyTo() {
        assertTemplateNotFound(bulk(VERIFIED, GHOST_TEMPLATE, "",
                "{\"Destination\": {\"ToAddresses\": [\"bad\"]}}"));
        assertTemplateNotFound(bulk(VERIFIED, GHOST_TEMPLATE, "\"ReplyToAddresses\": [\"bad\"], ", ENTRY));
    }

    @Test
    void sendBulkEmail_noEntryWithARecipient_answersEveryEntryBeforeSenderAndTemplate() {
        for (String sender : new String[] {UNVERIFIED, VERIFIED}) {
            assertEveryEntryEmpty(bulk(sender, GHOST_TEMPLATE, "", NO_RECIPIENT_ENTRIES));
        }
    }

    @Test
    void sendBulkEmail_noEntryWithARecipient_answersEveryEntryBeforeUnknownConfigurationSet() {
        assertEveryEntryEmpty(bulk(UNVERIFIED, GHOST_TEMPLATE, GHOST_CONFIG_SET_MEMBER, NO_RECIPIENT_ENTRIES));
        assertEveryEntryEmpty(bulk(UNVERIFIED, INLINE_TEMPLATE, GHOST_CONFIG_SET_MEMBER, NO_RECIPIENT_ENTRIES));
    }

    @Test
    void sendBulkEmail_oneEntryWithARecipient_stillReportsUnknownConfigurationSet() {
        assertConfigSetNotFound(bulk(UNVERIFIED, GHOST_TEMPLATE, GHOST_CONFIG_SET_MEMBER,
                "{\"Destination\": {}}, " + ENTRY));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"TemplateName\": \"template-order-ghost\"",
        "\"TemplateName\": \"template-order-ghost\", \"TemplateData\": \"\"",
        "\"TemplateName\": \"template-order-ghost\", \"TemplateData\": null",
        "\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}",
        "\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"\""
    })
    void sendBulkEmail_missingDefaultTemplateDataPrecedesEveryLookup(String template) {
        String content = "{\"Template\": {" + template + "}}";

        assertBadRequest(bulk(UNVERIFIED, content, "", ENTRY), "The default template data does not exist");
        assertBadRequest(bulk(UNVERIFIED, content, GHOST_CONFIG_SET_MEMBER, ENTRY),
                "The default template data does not exist");
        assertBadRequest(bulk(UNVERIFIED, content, GHOST_CONFIG_SET_MEMBER, NO_RECIPIENT_ENTRIES),
                "The default template data does not exist");
    }

    @Test
    void sendBulkEmail_replacementTemplateDataStaysOptional() {
        assertTemplateNotFound(bulk(VERIFIED, GHOST_TEMPLATE, "",
                "{\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"ReplacementEmailContent\": "
                        + "{\"ReplacementTemplate\": {}}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"TemplateName\": \"template-order-ghost\"",
        "\"TemplateName\": \"template-order-ghost\", \"TemplateData\": \"\"",
        "\"TemplateName\": \"template-order-ghost\", \"TemplateData\": null",
        "\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}",
        "\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"\""
    })
    void sendEmail_missingTemplateDataPrecedesEveryLookup(String template) {
        for (String members : new String[] {"", GHOST_CONFIG_SET_MEMBER}) {
            assertBadRequest(post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\", "
                    + members + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"Content\": {\"Template\": {"
                    + template + "}}}"), "Template data cannot be empty");
        }
    }

    @Test
    void sendEmail_unverifiedSenderPrecedesUnknownTemplate() {
        assertNotVerified(send(UNVERIFIED, ""), UNVERIFIED);
    }

    @Test
    void sendEmail_unknownConfigurationSetPrecedesUnverifiedSender() {
        assertConfigSetNotFound(send(UNVERIFIED, "\"ConfigurationSetName\": \"" + GHOST_CONFIG_SET + "\", "));
    }

    @Test
    void sendEmail_unverifiedFeedbackForwardingAddressPrecedesUnknownTemplate() {
        assertNotVerified(send(VERIFIED, "\"FeedbackForwardingEmailAddress\": \"" + UNVERIFIED + "\", "),
                UNVERIFIED);
    }

    @Test
    void sendEmail_verifiedSender_unknownTemplate() {
        assertTemplateNotFound(send(VERIFIED, ""));
    }

    @Test
    void sendTemplatedEmail_unverifiedSourcePrecedesUnknownTemplate() {
        assertV1Error(templated(UNVERIFIED), "MessageRejected", NOT_VERIFIED_MESSAGE + UNVERIFIED);
    }

    @Test
    void sendTemplatedEmail_unverifiedReturnPathPrecedesUnknownTemplate() {
        assertV1Error(templated(VERIFIED, "ReturnPath", UNVERIFIED), "MessageRejected",
                NOT_VERIFIED_MESSAGE + UNVERIFIED);
    }

    @Test
    void sendTemplatedEmail_unknownConfigurationSetPrecedesUnverifiedSource() {
        assertV1Error(templated(UNVERIFIED, "ConfigurationSetName", GHOST_CONFIG_SET),
                "ConfigurationSetDoesNotExist", "Configuration set &lt;" + GHOST_CONFIG_SET + "&gt; does not exist.");
    }

    @Test
    void sendTemplatedEmail_verifiedSource_unknownTemplate() {
        assertV1Error(templated(VERIFIED), "TemplateDoesNotExist",
                "Template " + V1_GHOST_TEMPLATE + " does not exist.");
    }

    @Test
    void sendBulkTemplatedEmail_unverifiedSourcePrecedesUnknownTemplate() {
        assertV1Error(bulkTemplated(UNVERIFIED, 1), "MessageRejected", NOT_VERIFIED_MESSAGE + UNVERIFIED);
    }

    @Test
    void sendBulkTemplatedEmail_unverifiedReturnPathPrecedesUnknownTemplate() {
        assertV1Error(bulkTemplated(VERIFIED, 1, "ReturnPath", UNVERIFIED), "MessageRejected",
                NOT_VERIFIED_MESSAGE + UNVERIFIED);
    }

    @Test
    void sendBulkTemplatedEmail_unknownConfigurationSetPrecedesUnverifiedSource() {
        assertV1Error(bulkTemplated(UNVERIFIED, 1, "ConfigurationSetName", GHOST_CONFIG_SET),
                "ConfigurationSetDoesNotExist", "Configuration set &lt;" + GHOST_CONFIG_SET + "&gt; does not exist.");
    }

    @Test
    void templateArnChecksPrecedeMissingTemplateData() {
        String content = "{\"Template\": {\"TemplateArn\": \"not-an-arn\"}}";

        assertBadRequest(bulk(UNVERIFIED, content, "", ENTRY), "ARNs must start with 'arn:': not-an-arn");
        assertBadRequest(post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"Content\": " + content + "}"),
                "ARNs must start with 'arn:': not-an-arn");
    }

    @Test
    void sendBulkTemplatedEmail_noEntryWithARecipient_stillReportsUnknownConfigurationSet() {
        // Not probed on v1: it keeps the order it had, unlike SendBulkEmail.
        assertV1Error(bulkTemplated(UNVERIFIED, 0, "ConfigurationSetName", GHOST_CONFIG_SET,
                        "Destinations.member.1.Destination.ToAddresses", ""),
                "ConfigurationSetDoesNotExist", "Configuration set &lt;" + GHOST_CONFIG_SET + "&gt; does not exist.");
    }

    @Test
    void sendBulkTemplatedEmail_verifiedSource_unknownTemplate() {
        assertV1Error(bulkTemplated(VERIFIED, 1), "TemplateDoesNotExist",
                "Template " + V1_GHOST_TEMPLATE + " does not exist.");
    }

    @Test
    void sendBulkTemplatedEmail_destinationCountPrecedesUnverifiedSource() {
        assertV1Error(bulkTemplated(UNVERIFIED, 51), "InvalidParameterValue",
                "You must specify at least 1 destination, and no more than 50 destinations");
    }
}
