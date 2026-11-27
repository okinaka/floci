package io.floci.aws.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ses.model.BulkEmailEntry;
import io.floci.aws.services.ses.model.BulkEmailEntryResult;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.EmailInsights;
import io.floci.aws.services.ses.model.EmailTemplate;
import io.floci.aws.services.ses.model.InsightsEvent;
import io.floci.aws.services.ses.model.SendBulkEmailRequest;
import io.floci.aws.services.ses.model.SendEmailRequest;
import io.floci.aws.services.ses.model.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A templated send whose data misses a template variable is accepted and never delivered; the
 * Rendering Failure message is all that is kept of it (probed 2026-11-27 on v1 and v2, single and
 * bulk, stored and inline).
 */
class SesServiceRenderingFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String SENDER = "from@example.com";
    private static final String TO = "to@example.com";
    private static final String TEMPLATE = "welcome";

    private SesServiceTestBuilder builder;
    private SmtpRelay smtpRelay;
    private SesService service;

    @BeforeEach
    void setUp() {
        smtpRelay = mock(SmtpRelay.class);
        builder = SesServiceTestBuilder.create().smtpRelay(smtpRelay);
        service = builder.build();
        builder.identityService().verifyEmailIdentity(SENDER, REGION);
    }

    private void storeTemplate(String subject, String text, String html) {
        builder.templateStore().put("template::" + REGION + "::" + TEMPLATE,
                new EmailTemplate(TEMPLATE, subject, text, html));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    private String send(EmailContent content) {
        return service.sendEmail(SendEmailRequest.builder()
                .source(SENDER)
                .toAddresses(List.of(TO))
                .region(REGION)
                .content(content)
                .build());
    }

    private SentEmail stored(String messageId) {
        return builder.sentEmailService().find(REGION, messageId).orElseThrow();
    }

    private static List<String> timeline(SentEmail email) {
        EmailInsights insights = email.getInsights().get(0);
        return insights.events().stream().map(InsightsEvent::type).toList();
    }

    static Stream<Arguments> missingVariables() {
        return Stream.of(
                Arguments.of("in the subject", "Hi {{name}}", "text", "<p>html</p>", "{}", "name"),
                Arguments.of("in the text part", "s", "Hi {{name}}", "<p>html</p>", "{}", "name"),
                Arguments.of("in the html part", "s", "text", "<p>{{name}}</p>", "{}", "name"),
                Arguments.of("beside a present one", "Hi {{name}}", "team {{team}}", null,
                        "{\"name\":\"Alice\"}", "team"),
                Arguments.of("null value", "Hi {{name}}", "text", null, "{\"name\":null}", "name"),
                Arguments.of("nested, parent absent", "s", "Hi {{user.name}}", null, "{}", "user.name"),
                Arguments.of("nested, parent empty", "s", "Hi {{user.name}}", null, "{\"user\":{}}",
                        "user.name"),
                Arguments.of("nested, parent not an object", "s", "Hi {{user.name}}", null,
                        "{\"user\":\"Alice\"}", "user.name"),
                Arguments.of("triple stash", "s", "Hi {{{name}}}", null, "{}", "name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingVariables")
    void storedTemplate_missingVariable_isAcceptedAndRecordedWithoutContent(
            String label, String subject, String text, String html, String data, String attribute) {
        storeTemplate(subject, text, html);

        String messageId = send(new EmailContent.Template(TEMPLATE, json(data), List.of()));

        assertNotNull(messageId);
        SentEmail email = stored(messageId);
        assertEquals("Attribute '" + attribute + "' is not present in the rendering data.",
                email.getRenderingFailure());
        assertNull(email.getRejectReason());
        assertNull(email.getSubject());
        assertNull(email.getBodyText());
        assertNull(email.getBodyHtml());
        assertEquals(List.of(TO), email.getToAddresses());
        assertEquals(List.of("SEND", "RENDERING_FAILURE"), timeline(email));
        verify(smtpRelay, never()).relay(any());
    }

    @Test
    void inlineTemplate_missingVariable_isAccepted() {
        String messageId = send(new EmailContent.InlineTemplate("Hi {{name}}", "text", null, json("{}"),
                List.of()));

        assertEquals("Attribute 'name' is not present in the rendering data.",
                stored(messageId).getRenderingFailure());
        verify(smtpRelay, never()).relay(any());
    }

    @Test
    void nestedVariable_presentInTheData_isRendered() {
        storeTemplate("Hi {{user.name}}", "team {{ user.team.name }}", null);

        String messageId = send(new EmailContent.Template(TEMPLATE,
                json("{\"user\":{\"name\":\"Alice\",\"team\":{\"name\":\"floci\"}}}"), List.of()));

        SentEmail email = stored(messageId);
        assertNull(email.getRenderingFailure());
        assertEquals("Hi Alice", email.getSubject());
        assertEquals("team floci", email.getBodyText());
        verify(smtpRelay, times(1)).relay(any());
    }

    @Test
    void arrayIndex_inAPath_isRendered() {
        storeTemplate("s", "Hi {{items.0.name}} and {{items.1.name}}", null);

        String messageId = send(new EmailContent.Template(TEMPLATE,
                json("{\"items\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}"), List.of()));

        SentEmail email = stored(messageId);
        assertNull(email.getRenderingFailure());
        assertEquals("Hi Alice and Bob", email.getBodyText());
    }

    @Test
    void arrayIndex_pastTheEnd_isMissing() {
        storeTemplate("s", "Hi {{items.2.name}}", null);

        String messageId = send(new EmailContent.Template(TEMPLATE,
                json("{\"items\":[{\"name\":\"Alice\"}]}"), List.of()));

        assertEquals("Attribute 'items.2.name' is not present in the rendering data.",
                stored(messageId).getRenderingFailure());
    }

    // Blocks are not evaluated, so a path into a block's context is sent as written, as it was
    // before dotted paths were read.
    @Test
    void pathRelativeToABlockContext_isLeftAsWritten() {
        storeTemplate("s", "{{#each items}}{{this.name}}{{/each}}", null);

        String messageId = send(new EmailContent.Template(TEMPLATE, json("{\"items\":[]}"), List.of()));

        SentEmail email = stored(messageId);
        assertNull(email.getRenderingFailure());
        assertEquals("{{#each items}}{{this.name}}{{/each}}", email.getBodyText());
    }

    @Test
    void bulk_entryMissingAVariable_succeedsBesideTheEntryThatRenders() {
        storeTemplate("Hi {{name}}", "team {{team}}", null);

        List<BulkEmailEntryResult> results = service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .region(REGION)
                .defaultContent(new EmailContent.Template(TEMPLATE, json("{\"team\":\"floci\"}"), List.of()))
                .entries(List.of(
                        new BulkEmailEntry(List.of("alice@example.com"), null, null,
                                json("{\"name\":\"Alice\"}"), null, null),
                        new BulkEmailEntry(List.of("bob@example.com"), null, null, null, null, null)))
                .build());

        assertEquals(List.of(BulkEmailEntryResult.Status.SUCCESS, BulkEmailEntryResult.Status.SUCCESS),
                results.stream().map(BulkEmailEntryResult::getStatus).toList());
        SentEmail rendered = stored(results.get(0).getMessageId());
        assertNull(rendered.getRenderingFailure());
        assertEquals("Hi Alice", rendered.getSubject());
        SentEmail failed = stored(results.get(1).getMessageId());
        assertEquals("Attribute 'name' is not present in the rendering data.", failed.getRenderingFailure());
        assertEquals(List.of("bob@example.com"), failed.getToAddresses());
        verify(smtpRelay, times(1)).relay(any());
    }

    @Test
    void bulk_inlineTemplateMissingAVariable_succeeds() {
        List<BulkEmailEntryResult> results = service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .region(REGION)
                .defaultContent(new EmailContent.InlineTemplate("Hi {{name}}", "text", null, json("{}"),
                        List.of()))
                .entries(List.of(new BulkEmailEntry(List.of(TO), null, null, null, null, null)))
                .build());

        assertEquals(BulkEmailEntryResult.Status.SUCCESS, results.get(0).getStatus());
        assertNotNull(stored(results.get(0).getMessageId()).getRenderingFailure());
        verify(smtpRelay, never()).relay(any());
    }
}
