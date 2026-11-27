package io.floci.aws.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.ses.SesV2Requests.AUTH;
import static io.floci.aws.services.ses.SesV2Requests.v2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A templated send whose data misses a template variable: the send answers 200 with a MessageId on
 * every surface, and the configuration set's event destination receives a Send and a Rendering
 * Failure event and no Delivery (probed against real SES on 2026-11-27).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesRenderingFailureIntegrationTest {

    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sqs/aws4_request";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";

    private static final String CS = "rendering-failure-cs";
    private static final String SENDER = "rendering-failure@floci.test";
    private static final String TEMPLATE = "rendering-failure-welcome";
    private static final String TO = "success@simulator.amazonses.com";
    private static final String MISSING_NAME = "Attribute 'name' is not present in the rendering data.";
    private static final Pattern V1_MESSAGE_ID = Pattern.compile("<MessageId>([^<]+)</MessageId>");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String queueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpEventDestinationAndTemplate() {
        queueUrl = given().contentType(JSON_1_0).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"rendering-failure-queue\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        String queueArn = given().contentType(JSON_1_0).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        String topicArn = given().contentType(JSON_1_0).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .body("{\"Name\":\"rendering-failure-topic\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("TopicArn");
        given().contentType(JSON_1_0).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\"" + queueArn + "\"}")
        .when().post("/").then().statusCode(200);

        v2().body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        v2().body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);
        v2().body("""
                {"EventDestinationName": "ed-sns",
                 "EventDestination": {
                   "Enabled": true,
                   "MatchingEventTypes": ["SEND", "DELIVERY", "RENDERING_FAILURE"],
                   "SnsDestination": {"TopicArn": "%s"}}}
                """.formatted(topicArn))
        .when().post("/v2/email/configuration-sets/" + CS + "/event-destinations").then().statusCode(200);
        v2().body("""
                {"TemplateName": "%s",
                 "TemplateContent": {"Subject": "Hello {{name}}", "Text": "Hi {{name}}, team {{team}}!"}}
                """.formatted(TEMPLATE))
        .when().post("/v2/email/templates").then().statusCode(200);
    }

    @Test
    @Order(2)
    void v2SendEmail_storedTemplate_isAcceptedThenPublishesSendAndRenderingFailure() throws Exception {
        String messageId = v2().body("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["%s"]},
                 "ConfigurationSetName": "%s",
                 "EmailTags": [{"Name": "campaign", "Value": "launch"}],
                 "Content": {"Template": {"TemplateName": "%s", "TemplateData": "{\\"team\\":\\"floci\\"}",
                                          "Headers": [{"Name": "X-Campaign", "Value": "unrendered"}]}}}
                """.formatted(SENDER, TO, CS, TEMPLATE))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue())
                .extract().jsonPath().getString("MessageId");

        List<JsonNode> events = eventsOf(messageId, 2);
        assertEquals(List.of("Send", "Rendering Failure"), types(events));

        JsonNode failure = events.get(1);
        assertEquals(MISSING_NAME, failure.path("failure").path("errorMessage").asText());
        assertEquals(TEMPLATE, failure.path("failure").path("templateName").asText());
        JsonNode mail = failure.path("mail");
        assertEquals(SENDER, mail.path("source").asText());
        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/" + SENDER, mail.path("sourceArn").asText());
        assertEquals("000000000000", mail.path("sendingAccountId").asText());
        assertEquals(TO, mail.path("destination").get(0).asText());
        assertTrue(mail.has("timestamp"));
        assertFalse(mail.path("headersTruncated").asBoolean(true));
        assertFalse(mail.has("headers"));
        assertFalse(mail.has("commonHeaders"));
        assertEquals(CS, mail.path("tags").path("ses:configuration-set").get(0).asText());
        assertEquals("launch", mail.path("tags").path("campaign").get(0).asText());

        // The Send event of the failed send carries no Subject: nothing was rendered.
        JsonNode sendMail = events.get(0).path("mail");
        assertFalse(sendMail.path("commonHeaders").has("subject"));
        assertFalse(sendMail.path("headers").toString().contains("\"Subject\""));
        // Nor the request's headers, which were never scanned.
        assertFalse(sendMail.path("headers").toString().contains("X-Campaign"));

        // The record keeps the envelope and the failure, and no content.
        given().header("Authorization", AUTH)
        .when().get("/_aws/ses?id=" + messageId).then().statusCode(200)
                .body("messages[0].RenderingFailure", equalTo(MISSING_NAME))
                .body("messages[0].Destination.ToAddresses", contains(TO))
                .body("messages[0]", not(hasKey("RejectReason")))
                .body("messages[0]", not(hasKey("Subject")))
                .body("messages[0]", not(hasKey("Body")));
    }

    @Test
    @Order(3)
    void v2SendEmail_inlineTemplate_publishesANullTemplateName() throws Exception {
        String messageId = v2().body("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["%s"]},
                 "ConfigurationSetName": "%s",
                 "Content": {"Template": {
                   "TemplateContent": {"Subject": "s", "Html": "<p>{{user.name}}</p>"},
                   "TemplateData": "{\\"user\\":{}}"}}}
                """.formatted(SENDER, TO, CS))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .extract().jsonPath().getString("MessageId");

        List<JsonNode> events = eventsOf(messageId, 2);
        assertEquals(List.of("Send", "Rendering Failure"), types(events));
        JsonNode failure = events.get(1).path("failure");
        assertEquals("Attribute 'user.name' is not present in the rendering data.",
                failure.path("errorMessage").asText());
        assertTrue(failure.has("templateName"));
        assertTrue(failure.path("templateName").isNull());
    }

    @Test
    @Order(4)
    void v2SendEmail_withoutAConfigurationSet_isAccepted() {
        v2().body("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["%s"]},
                 "Content": {"Template": {"TemplateName": "%s", "TemplateData": "{}"}}}
                """.formatted(SENDER, TO, TEMPLATE))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(5)
    void v2SendBulkEmail_everyEntrySucceedsAndOnlyTheIncompleteOneFails() throws Exception {
        Response response = v2().body("""
                {"FromEmailAddress": "%s",
                 "ConfigurationSetName": "%s",
                 "DefaultContent": {"Template": {"TemplateName": "%s", "TemplateData": "{\\"team\\":\\"floci\\"}"}},
                 "BulkEmailEntries": [
                   {"Destination": {"ToAddresses": ["%s"]},
                    "ReplacementEmailContent": {"ReplacementTemplate": {
                      "ReplacementTemplateData": "{\\"name\\":\\"Alice\\"}"}}},
                   {"Destination": {"ToAddresses": ["%s"]}}]}
                """.formatted(SENDER, CS, TEMPLATE, TO, TO))
        .when().post("/v2/email/outbound-bulk-emails").then().statusCode(200)
                .body("BulkEmailEntryResults.Status", contains("SUCCESS", "SUCCESS"))
                .extract().response();
        String complete = response.jsonPath().getString("BulkEmailEntryResults[0].MessageId");
        String incomplete = response.jsonPath().getString("BulkEmailEntryResults[1].MessageId");
        assertNotNull(complete);
        assertNotNull(incomplete);

        List<JsonNode> events = eventsOf(List.of(complete, incomplete), 4);
        assertEquals(List.of("Send", "Delivery"), types(of(events, complete)));
        assertEquals(List.of("Send", "Rendering Failure"), types(of(events, incomplete)));
    }

    @Test
    @Order(6)
    void v1SendTemplatedEmail_isAcceptedThenPublishesRenderingFailure() throws Exception {
        String body = SesV1Requests.query("SendTemplatedEmail")
                .formParam("Source", SENDER)
                .formParam("Destination.ToAddresses.member.1", TO)
                .formParam("Template", TEMPLATE)
                .formParam("TemplateData", "{\"name\":\"Alice\",\"team\":null}")
                .formParam("ConfigurationSetName", CS)
        .when().post("/").then().statusCode(200)
                .extract().body().asString();
        String messageId = v1MessageIds(body).get(0);

        List<JsonNode> events = eventsOf(messageId, 2);
        assertEquals(List.of("Send", "Rendering Failure"), types(events));
        assertEquals("Attribute 'team' is not present in the rendering data.",
                events.get(1).path("failure").path("errorMessage").asText());
        assertEquals(TEMPLATE, events.get(1).path("failure").path("templateName").asText());
    }

    @Test
    @Order(7)
    void v1SendBulkTemplatedEmail_everyEntrySucceedsAndOnlyTheIncompleteOneFails() throws Exception {
        String body = SesV1Requests.query("SendBulkTemplatedEmail")
                .formParam("Source", SENDER)
                .formParam("Template", TEMPLATE)
                .formParam("DefaultTemplateData", "{\"team\":\"floci\"}")
                .formParam("ConfigurationSetName", CS)
                .formParam("Destinations.member.1.Destination.ToAddresses.member.1", TO)
                .formParam("Destinations.member.1.ReplacementTemplateData", "{\"name\":\"Alice\"}")
                .formParam("Destinations.member.2.Destination.ToAddresses.member.1", TO)
        .when().post("/").then().statusCode(200)
                .extract().body().asString();

        assertEquals(2, body.split("<Status>Success</Status>", -1).length - 1, body);
        List<String> messageIds = v1MessageIds(body);
        assertEquals(2, messageIds.size(), body);

        List<JsonNode> events = eventsOf(messageIds, 4);
        assertEquals(List.of("Send", "Delivery"), types(of(events, messageIds.get(0))));
        assertEquals(List.of("Send", "Rendering Failure"), types(of(events, messageIds.get(1))));
    }

    private static List<String> v1MessageIds(String body) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = V1_MESSAGE_ID.matcher(body);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static List<String> types(List<JsonNode> events) {
        return events.stream().map(event -> event.path("eventType").asText()).toList();
    }

    private static List<JsonNode> of(List<JsonNode> events, String messageId) {
        return events.stream()
                .filter(event -> messageId.equals(event.path("mail").path("messageId").asText()))
                .toList();
    }

    private List<JsonNode> eventsOf(String messageId, int expected) throws Exception {
        return eventsOf(List.of(messageId), expected);
    }

    // Collects the events of the given messages in arrival order, then polls once more so an
    // unexpected extra event (a Delivery after the failure) is seen rather than left in the queue.
    private List<JsonNode> eventsOf(List<String> messageIds, int expected) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        boolean settled = false;
        for (int attempt = 0; attempt < 10 && !settled; attempt++) {
            settled = events.size() >= expected;
            Response response = given().contentType(JSON_1_0).header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when().post("/").then().statusCode(200).extract().response();
            List<String> bodies = response.jsonPath().getList("Messages.Body");
            List<String> handles = response.jsonPath().getList("Messages.ReceiptHandle");
            if (bodies == null || bodies.isEmpty()) {
                continue;
            }
            for (String body : bodies) {
                JsonNode event = MAPPER.readTree(MAPPER.readTree(body).path("Message").asText());
                if (messageIds.contains(event.path("mail").path("messageId").asText())) {
                    events.add(event);
                }
            }
            for (String handle : handles) {
                given().contentType(JSON_1_0).header("Authorization", SQS_AUTH)
                        .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
                        .body(MAPPER.createObjectNode().put("QueueUrl", queueUrl).put("ReceiptHandle", handle)
                                .toString())
                .when().post("/").then().statusCode(200);
            }
        }
        return events;
    }
}
