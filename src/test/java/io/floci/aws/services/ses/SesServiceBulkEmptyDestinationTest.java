package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ses.model.BulkEmailEntry;
import io.floci.aws.services.ses.model.BulkEmailEntryResult;
import io.floci.aws.services.ses.model.ConfigurationSet;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.SendBulkEmailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A v2 SendBulkEmail in which no entry names a recipient answers every entry on its own, ahead of
 * the configuration set lookup, the sending-paused gate of the set and the stored template lookup
 * (probe-confirmed). With one addressed entry the request-level checks run as usual. The v1
 * SendBulkTemplatedEmail has not been probed for this and keeps the configuration set first.
 */
class SesServiceBulkEmptyDestinationTest {

    private static final String REGION = "us-east-1";
    private static final String SENDER = "from@example.com";
    private static final String GHOST_SET = "ghost-set";
    private static final String PAUSED_SET = "paused-set";
    private static final String ENABLED_SET = "enabled-set";
    private static final EmailContent.InlineTemplate INLINE =
            new EmailContent.InlineTemplate("s", "t", null, null, List.of());
    private static final BulkEmailEntry EMPTY = new BulkEmailEntry(List.of(), null, null, null, null, null);
    private static final BulkEmailEntry ADDRESSED =
            new BulkEmailEntry(List.of("to@example.com"), null, null, null, null, null);

    private SesService service;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        service = builder.build();
        service.createConfigurationSet(new ConfigurationSet(ENABLED_SET), REGION);
        service.createConfigurationSet(new ConfigurationSet(PAUSED_SET), REGION);
        builder.configSetService().setSendingEnabled(PAUSED_SET, false, REGION);
    }

    @Test
    void noRecipient_unknownConfigurationSet_answersEveryEntry() {
        assertEveryEntryEmpty(service.sendBulkEmail(request(GHOST_SET, INLINE, EMPTY, EMPTY).build()), 2);
    }

    @Test
    void noRecipient_pausedConfigurationSet_answersEveryEntry() {
        assertEveryEntryEmpty(service.sendBulkEmail(request(PAUSED_SET, INLINE, EMPTY).build()), 1);
    }

    @Test
    void noRecipient_unknownConfigurationSetAndMissingTemplate_answersEveryEntry() {
        EmailContent.Template missing = new EmailContent.Template("missing", null, List.of());

        assertEveryEntryEmpty(service.sendBulkEmail(request(GHOST_SET, missing, EMPTY).build()), 1);
    }

    @Test
    void oneAddressedEntry_unknownConfigurationSet_isRefused() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.sendBulkEmail(request(GHOST_SET, INLINE, EMPTY, ADDRESSED).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void oneAddressedEntry_pausedConfigurationSet_isRefused() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.sendBulkEmail(request(PAUSED_SET, INLINE, EMPTY, ADDRESSED).build()));

        assertEquals("ConfigurationSetSendingPausedException", e.getErrorCode());
    }

    @Test
    void v1Order_noRecipient_unknownConfigurationSet_isRefused() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(
                request(GHOST_SET, INLINE, EMPTY).configurationSetBeforeEmptyDestinations(true).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void v1Order_noRecipient_knownConfigurationSet_answersEveryEntry() {
        assertEveryEntryEmpty(service.sendBulkEmail(
                request(ENABLED_SET, INLINE, EMPTY).configurationSetBeforeEmptyDestinations(true).build()), 1);
    }

    private static SendBulkEmailRequest.Builder request(String configurationSet, EmailContent.TemplateContent content,
                                                        BulkEmailEntry... entries) {
        return SendBulkEmailRequest.builder()
                .source(SENDER)
                .region(REGION)
                .configurationSetName(configurationSet)
                .defaultContent(content)
                .entries(List.of(entries));
    }

    private static void assertEveryEntryEmpty(List<BulkEmailEntryResult> results, int expected) {
        assertEquals(expected, results.size());
        for (BulkEmailEntryResult result : results) {
            assertEquals(BulkEmailEntryResult.Status.INVALID_PARAMETER, result.getStatus());
            assertEquals("Destination cannot be empty", result.getError());
            assertNull(result.getMessageId());
        }
    }
}
