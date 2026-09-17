package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The SES send path: assembling a message from its v1 or v2 request shape, applying the account,
 * tenant and configuration-set gates, filtering suppressed recipients, handing the result to
 * {@link SmtpRelay} and recording it, then publishing the send events. Sending is what SES exists
 * for, so this stays the service the SES controllers and the v1 handler reach for first. It reads
 * across several domains and, like {@link SesCrossDomainService}, owns no store of its own.
 */
@ApplicationScoped
public class SesService {

    private static final Logger LOG = Logger.getLogger(SesService.class);

    private static final int MAX_BULK_DESTINATIONS = 50;
    private static final int MAX_RECIPIENTS_PER_DESTINATION = 50;
    private static final String CUSTOM_VERIFICATION_DISCLAIMER =
            "If you did not request to verify this email address, please disregard this message.";
    private static final String UNSUBSCRIBE_PLACEHOLDER = "{{amazonSESUnsubscribeUrl}}";

    private final SesIdentityService identityService;
    private final SesSentEmailService sentEmailService;
    private final SesTemplateService templateService;
    private final SesConfigurationSetService configSetService;
    private final SesSuppressionService suppressionService;
    private final SesContactService contactService;
    private final SesCvetService cvetService;
    private final SesTenantService tenantService;
    private final SmtpRelay smtpRelay;
    private final SesEventPublisher eventPublisher;
    private final String defaultAccountId;
    // Base URL used to build functional list-management unsubscribe links (the {{amazonSESUnsubscribeUrl}}
    // placeholder and the List-Unsubscribe header) that resolve to Floci's own unsubscribe endpoint.
    private final String baseUrl;
    // Resolves the caller's account per request so send-event payloads report the sending account, not
    // the fixed default. Null in the package-private test constructor (falls back to defaultAccountId).
    private final RegionResolver regionResolver;

    @Inject
    public SesService(SesIdentityService identityService, SesSentEmailService sentEmailService,
                      SesTemplateService templateService,
                      SesConfigurationSetService configSetService,
                      SesSuppressionService suppressionService, SesContactService contactService,
                      SesCvetService cvetService, SesTenantService tenantService,
                      SmtpRelay smtpRelay, SesEventPublisher eventPublisher,
                      EmulatorConfig config, RegionResolver regionResolver) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.contactService = contactService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.smtpRelay = smtpRelay;
        this.eventPublisher = eventPublisher;
        this.defaultAccountId = config.defaultAccountId();
        this.baseUrl = config.effectiveBaseUrl();
        this.regionResolver = regionResolver;
    }

    SesService(SesIdentityService identityService, SesSentEmailService sentEmailService,
                   SesTemplateService templateService, SesConfigurationSetService configSetService,
                   SesSuppressionService suppressionService, SesContactService contactService,
                   SesCvetService cvetService, SesTenantService tenantService,
                   SmtpRelay smtpRelay) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.contactService = contactService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.smtpRelay = smtpRelay;
        this.eventPublisher = null;
        this.defaultAccountId = "000000000000";
        this.baseUrl = "http://localhost:4566";
        this.regionResolver = null;
    }

    public String sendEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> replyToAddresses, String returnPath,
                            String subject, String bodyText, String bodyHtml,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, ListManagementOptions listManagement,
                            String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        boolean hasRecipient = (toAddresses != null && !toAddresses.isEmpty())
                || (ccAddresses != null && !ccAddresses.isEmpty())
                || (bccAddresses != null && !bccAddresses.isEmpty());
        if (!hasRecipient) {
            throw new AwsException("InvalidParameterValue", "At least one destination address is required.", 400);
        }
        String effectiveConfigSet = resolveDefaultConfigurationSet(configurationSetName, source, region);
        configSetService.validateForSending(effectiveConfigSet, region);

        // Resolve suppression before recording the message so a bad ListManagementOptions (e.g. an
        // unknown contact list) fails the whole send without leaving an orphaned SentEmail record.
        List<String> envelope = allRecipients(toAddresses, ccAddresses, bccAddresses);
        Map<String, String> suppressedReasons = new LinkedHashMap<>(
                collectSuppressedReasons(envelope, effectiveConfigSet, region));
        // putIfAbsent so a suppression-list reason already set for an address (e.g. COMPLAINT) wins
        // over the list-management BOUNCE and keeps its synthetic event type.
        contactService.collectListManagementOptOuts(envelope, listManagement, region,
                SesService::extractEmailAddress).forEach(suppressedReasons::putIfAbsent);

        // A single-recipient list-managed send gets a functional unsubscribe link: the
        // {{amazonSESUnsubscribeUrl}} body placeholder is replaced and the List-Unsubscribe headers
        // are added, matching AWS (which only injects these for a single recipient). The link
        // resolves to Floci's own /_aws/ses/unsubscribe endpoint.
        if (hasListManagement(listManagement) && envelope.size() == 1) {
            String url = buildUnsubscribeUrl(region, listManagement, extractEmailAddress(envelope.get(0)));
            bodyText = replaceUnsubscribePlaceholder(bodyText, url);
            bodyHtml = replaceUnsubscribePlaceholder(bodyHtml, url);
            additionalHeaders = withUnsubscribeHeaders(additionalHeaders, url);
        }

        // Drop unsafe user-supplied headers (blank name or CR/LF in name/value) once here, so the
        // stored SentEmail, the SMTP relay, and the published events all reflect the same sanitized
        // set and no injection payload is retained on any surface.
        if (additionalHeaders != null) {
            additionalHeaders = additionalHeaders.stream().filter(MessageHeader::isSafe).toList();
        }

        String messageId = UUID.randomUUID().toString();
        String effectiveReturnPath = firstNonBlank(returnPath, source);
        SentEmail email = new SentEmail(messageId, region, source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, subject, bodyText, bodyHtml);
        email.setReturnPath(effectiveReturnPath);
        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            email.setHeaders(additionalHeaders);
        }
        sentEmailService.record(region, messageId, email);

        List<String> relayedTo = filterUnsuppressed(toAddresses, suppressedReasons);
        List<String> relayedCc = filterUnsuppressed(ccAddresses, suppressedReasons);
        List<String> relayedBcc = filterUnsuppressed(bccAddresses, suppressedReasons);
        if (sizeOf(relayedTo) + sizeOf(relayedCc) + sizeOf(relayedBcc) > 0) {
            smtpRelay.relay(SmtpRelay.RelayMessage.builder(source)
                    .returnPath(effectiveReturnPath)
                    .to(relayedTo)
                    .cc(relayedCc)
                    .bcc(relayedBcc)
                    .replyTo(replyToAddresses)
                    .subject(subject)
                    .bodyText(bodyText)
                    .bodyHtml(bodyHtml)
                    .headers(additionalHeaders)
                    .messageId(messageId)
                    .build());
        } else {
            LOG.infov("SES email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES email sent: from={0}, to={1}, subject={2}, messageId={3}",
                source, toAddresses, subject, messageId);
        publishSendEvents(effectiveConfigSet, messageId, source, subject,
                toAddresses, ccAddresses, bccAddresses, envelope,
                suppressedReasons, emailTags, additionalHeaders, region);
        return messageId;
    }

    public String sendRawEmail(String source, List<String> destinations, String rawMessage,
                               String returnPath, String configurationSetName, List<MessageTag> emailTags,
                               ListManagementOptions listManagement, String region) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RawMessage.Data is required.", 400);
        }
        boolean hasExplicitDestinations = destinations != null && !destinations.isEmpty();
        boolean sourceOmitted = source == null || source.isBlank();
        // The MIME headers are always parsed: X-SES-CONFIGURATION-SET can name the configuration
        // set that decides whether events are published at all, so the configuration set cannot be
        // resolved before the message has been read.
        SmtpRelay.RawMessageHeaders headers = SmtpRelay.parseRawHeaders(rawMessage);
        // AWS accepts the configuration set either as a request field or as the
        // X-SES-CONFIGURATION-SET header on the message itself; the request field wins.
        String requestedConfigSet = firstNonBlank(configurationSetName, headers.configurationSet());
        String effectiveConfigSet = resolveDefaultConfigurationSet(requestedConfigSet, source, region);
        configSetService.validateForSending(effectiveConfigSet, region);
        // Message tags work differently: AWS uses only the request field's tags when both are
        // present and does not join the two sets, so the header tags apply only when no tag was
        // passed as a parameter.
        List<MessageTag> effectiveTags = (emailTags == null || emailTags.isEmpty())
                ? headers.messageTags()
                : emailTags;
        String effectiveSource = sourceOmitted && !headers.from().isBlank()
                ? headers.from()
                : source;
        if (effectiveSource == null || effectiveSource.isBlank()) {
            // Shared by the v1 Query and v2 REST surfaces. Throw the v1-native code; the v2
            // controller's remapV1Exception translates InvalidParameterValue -> BadRequestException.
            // Verified against real AWS: v1 returns InvalidParameterValue and v2 BadRequestException,
            // both with this message.
            throw new AwsException("InvalidParameterValue", "Missing required header 'From'.", 400);
        }
        // FromEmailAddress was omitted, so the configuration set couldn't be resolved from the
        // sender until the MIME "From" was parsed. Re-resolve from the effective sender now so an
        // email identity's default configuration set still applies to a Raw send without an
        // explicit FromEmailAddress.
        if (sourceOmitted && (requestedConfigSet == null || requestedConfigSet.isBlank())) {
            effectiveConfigSet = resolveDefaultConfigurationSet(requestedConfigSet, effectiveSource, region);
            configSetService.validateForSending(effectiveConfigSet, region);
        }
        List<String> effectiveDestinations = hasExplicitDestinations
                ? destinations
                : allRecipients(headers.to(), headers.cc(), headers.bcc());
        if (effectiveDestinations.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination address is required.", 400);
        }
        // Resolve suppression before recording the message so a bad ListManagementOptions (e.g. an
        // unknown contact list) fails the whole send without leaving an orphaned SentEmail record.
        Map<String, String> suppressedReasons = new LinkedHashMap<>(
                collectSuppressedReasons(effectiveDestinations, effectiveConfigSet, region));
        // putIfAbsent so a suppression-list reason already set for an address (e.g. COMPLAINT) wins
        // over the list-management BOUNCE and keeps its synthetic event type.
        contactService.collectListManagementOptOuts(effectiveDestinations, listManagement, region,
                        SesService::extractEmailAddress)
                .forEach(suppressedReasons::putIfAbsent);

        String messageId = UUID.randomUUID().toString();
        // AWS routes bounces to the Return-Path carried by the message, falling back to the
        // request's return path and then the sender.
        String effectiveReturnPath = firstNonBlank(headers.returnPath(), returnPath, effectiveSource);
        SentEmail email = new SentEmail(messageId, region, effectiveSource, effectiveDestinations, rawMessage);
        email.setReturnPath(effectiveReturnPath);
        sentEmailService.record(region, messageId, email);

        List<String> relayedDestinations = filterUnsuppressed(effectiveDestinations, suppressedReasons);
        if (!relayedDestinations.isEmpty()) {
            smtpRelay.relayRaw(new SmtpRelay.RawRelayMessage(effectiveSource, effectiveReturnPath,
                    relayedDestinations, rawMessage, messageId));
        } else {
            LOG.infov("SES raw email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES raw email sent: from={0}, messageId={1}", effectiveSource, messageId);
        publishSendEvents(effectiveConfigSet, messageId, effectiveSource,
                headers.subject(), headers.to(), headers.cc(), headers.bcc(),
                effectiveDestinations,
                suppressedReasons, effectiveTags, List.of(), region);
        return messageId;
    }

    private static List<String> allRecipients(List<String> to, List<String> cc, List<String> bcc) {
        List<String> all = new ArrayList<>();
        if (to != null) {
            all.addAll(to);
        }
        if (cc != null) {
            all.addAll(cc);
        }
        if (bcc != null) {
            all.addAll(bcc);
        }
        return all;
    }

    private void publishSendEvents(String configurationSetName, String messageId, String source,
                                   String subject, List<String> toAddresses,
                                   List<String> ccAddresses, List<String> bccAddresses,
                                   List<String> envelopeDestinations,
                                   Map<String, String> suppressedReasons,
                                   List<MessageTag> emailTags,
                                   List<MessageHeader> additionalHeaders, String region) {
        if (eventPublisher == null || messageId == null) {
            return;
        }
        ConfigurationSet cs = null;
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            cs = configSetService.find(configurationSetName, region).orElse(null);
            if (cs == null) {
                LOG.warnv("SES send references unknown ConfigurationSet <{0}>; configuration-set "
                        + "events not published (identity notifications, if any, still apply).",
                        configurationSetName);
            }
        }
        boolean configSetActive = cs != null && !cs.getEventDestinations().isEmpty();
        Map<String, IdentityNotificationTarget> identityTargets =
                resolveIdentityNotificationTargets(source, region);
        if (!configSetActive && identityTargets.isEmpty()) {
            return;
        }

        List<String> envelope = envelopeDestinations != null
                ? envelopeDestinations : Collections.emptyList();
        Instant timestamp = Instant.now();
        // Report the caller's account (resolved per request) in the event payload and source ARN,
        // falling back to the default account outside a request context (e.g. unit tests).
        String sendingAccountId = regionResolver != null ? regionResolver.getAccountId() : defaultAccountId;
        String sourceArn = (source == null || source.isBlank())
                ? null
                : AwsArnUtils.Arn.of("ses", region, sendingAccountId,
                        "identity/" + extractEmailAddress(source)).toString();

        List<String> suppressionBounceRecipients = new ArrayList<>();
        List<String> suppressionComplaintRecipients = new ArrayList<>();
        for (Map.Entry<String, String> e : suppressedReasons.entrySet()) {
            if ("BOUNCE".equals(e.getValue())) {
                suppressionBounceRecipients.add(e.getKey());
            } else if ("COMPLAINT".equals(e.getValue())) {
                suppressionComplaintRecipients.add(e.getKey());
            }
        }

        for (String eventType : determineSendEventTypes(envelope,
                suppressionBounceRecipients, suppressionComplaintRecipients)) {
            if (configSetActive) {
                eventPublisher.publish(cs, eventType, messageId, source, sourceArn, sendingAccountId,
                        subject, toAddresses, ccAddresses, bccAddresses, envelope,
                        suppressionBounceRecipients, suppressionComplaintRecipients,
                        emailTags, additionalHeaders, timestamp, region);
            }
            IdentityNotificationTarget target = identityTargets.get(eventType);
            if (target != null) {
                eventPublisher.publishIdentityNotification(target.topicArn(), target.includeHeaders(),
                        eventType, messageId, source, sourceArn, sendingAccountId, subject,
                        toAddresses, ccAddresses, bccAddresses, envelope, suppressionBounceRecipients,
                        suppressionComplaintRecipients, additionalHeaders, timestamp, region);
            }
        }
    }

    /**
     * Resolves the SNS feedback notification target configured via {@code SetIdentityNotificationTopic}
     * for the sending identity. The email-address identity's topic takes precedence over its parent
     * domain identity's topic, per notification type; the headers-in-notifications flag is read from
     * whichever identity supplied the topic. Returns a map keyed by the {@code SEND}-style event name
     * ({@code BOUNCE}/{@code COMPLAINT}/{@code DELIVERY}) so it can be looked up directly against
     * {@link #determineSendEventTypes}.
     */
    private Map<String, IdentityNotificationTarget> resolveIdentityNotificationTargets(String source,
                                                                                       String region) {
        Map<String, IdentityNotificationTarget> targets = new LinkedHashMap<>();
        if (source == null || source.isBlank()) {
            return targets;
        }
        String email = extractEmailAddress(source);
        if (email.isBlank()) {
            return targets;
        }
        Identity emailIdentity = identityService.find(email, region).orElse(null);
        Identity domainIdentity = null;
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            domainIdentity = identityService.find(email.substring(at + 1), region).orElse(null);
        }
        for (String type : SesIdentityService.NOTIFICATION_TYPES) {
            String topic = notificationTopicFor(emailIdentity, type);
            Identity owner = emailIdentity;
            if (topic == null) {
                topic = notificationTopicFor(domainIdentity, type);
                owner = domainIdentity;
            }
            if (topic == null) {
                continue;
            }
            boolean includeHeaders = Boolean.TRUE.equals(
                    owner.getHeadersInNotificationsEnabled().get(type));
            targets.put(type.toUpperCase(Locale.ROOT),
                    new IdentityNotificationTarget(topic, includeHeaders));
        }
        return targets;
    }

    private static String notificationTopicFor(Identity identity, String type) {
        if (identity == null) {
            return null;
        }
        String topic = identity.getNotificationAttributes().get(type + "Topic");
        return topic != null && !topic.isBlank() ? topic : null;
    }

    private record IdentityNotificationTarget(String topicArn, boolean includeHeaders) {}

    private static String extractEmailAddress(String source) {
        int open = source.indexOf('<');
        int close = source.indexOf('>', open + 1);
        if (open >= 0 && close > open) {
            return source.substring(open + 1, close).trim();
        }
        return source.trim();
    }

    private static List<String> determineSendEventTypes(List<String> destinations,
                                                        List<String> suppressionBounceRecipients,
                                                        List<String> suppressionComplaintRecipients) {
        List<String> events = new ArrayList<>();
        events.add("SEND");
        for (String d : destinations) {
            if (SimulatorAddresses.isSuccess(d) && !events.contains("DELIVERY")) {
                events.add("DELIVERY");
            }
            if (SimulatorAddresses.isBounce(d) && !events.contains("BOUNCE")) {
                events.add("BOUNCE");
            }
            if (SimulatorAddresses.isComplaint(d) && !events.contains("COMPLAINT")) {
                events.add("COMPLAINT");
            }
            if (SimulatorAddresses.isSuppressionList(d) && !events.contains("REJECT")) {
                events.add("REJECT");
            }
        }
        if (!suppressionBounceRecipients.isEmpty() && !events.contains("BOUNCE")) {
            events.add("BOUNCE");
        }
        if (!suppressionComplaintRecipients.isEmpty() && !events.contains("COMPLAINT")) {
            events.add("COMPLAINT");
        }
        return events;
    }

    /**
     * Resolves the configuration set a send should use: a non-blank configuration set explicitly
     * supplied by the caller takes precedence (a blank value is treated as absent); otherwise the
     * default configuration set associated with the sending identity (set via
     * {@code PutEmailIdentityConfigurationSetAttributes}) is used, with the email-address identity
     * taking precedence over its parent domain. If that default association is stale (its
     * configuration set was deleted), the send fails with a bad-request error, matching AWS.
     */
    private String resolveDefaultConfigurationSet(String configurationSetName, String source, String region) {
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            return configurationSetName;
        }
        if (source == null || source.isBlank()) {
            return configurationSetName;
        }
        String email = extractEmailAddress(source);
        if (email.isBlank()) {
            return configurationSetName;
        }
        String fromEmail = existingDefaultConfigSet(identityService.find(email, region).orElse(null), region);
        if (fromEmail != null) {
            return fromEmail;
        }
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            String fromDomain = existingDefaultConfigSet(
                    identityService.find(email.substring(at + 1), region).orElse(null), region);
            if (fromDomain != null) {
                return fromDomain;
            }
        }
        return configurationSetName;
    }

    private String existingDefaultConfigSet(Identity identity, String region) {
        if (identity == null) {
            return null;
        }
        String cs = identity.getConfigurationSetName();
        if (cs == null || cs.isEmpty()) {
            return null;
        }
        // AWS: deleting the configuration set that is an identity's default, then sending through
        // that identity, fails with a bad-request error rather than silently sending without it.
        if (configSetService.find(cs, region).isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Configuration set <" + cs + "> does not exist.", 400);
        }
        return cs;
    }

    public String sendCustomVerificationEmail(String emailAddress, String templateName,
                                              String configurationSetName, String region) {
        // AWS validates the recipient before the template exists check (probe-confirmed): a blank
        // address is "Email address not specified."; anything that isn't a single valid address —
        // no local-part/domain separator, more than one separator, whitespace, or longer than 320
        // chars — is "Invalid email address<addr>." (both InvalidParameterValue / 400, remapped to
        // BadRequestException on v2). The separator count ignores '@' inside a quoted local part,
        // since AWS accepts an RFC-5321 quoted local part that contains '@' (e.g. "a@b"@example.com).
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Email address not specified.", 400);
        }
        // A leading/trailing '@' means an empty local part (@example.com) or empty domain (local@),
        // both of which AWS rejects (probe-confirmed). AWS does not require a dot in the domain
        // (a@b is accepted), so no stricter domain shape is enforced.
        boolean emptyBoundary = emailAddress.charAt(0) == '@'
                || emailAddress.charAt(emailAddress.length() - 1) == '@';
        if (emailAddress.length() > 320 || unquotedAtCount(emailAddress) != 1 || emptyBoundary
                || emailAddress.chars().anyMatch(Character::isWhitespace)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid email address<" + emailAddress + ">.", 400);
        }
        if (templateName == null || templateName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TemplateName is required.", 400);
        }
        CustomVerificationEmailTemplate template = cvetService.find(templateName, region)
                .orElseThrow(() -> new AwsException("CustomVerificationEmailTemplateDoesNotExist",
                        "Template <" + templateName + "> does not exist", 400));
        if (!identityService.isVerifiedSender(template.getFromEmailAddress(), region)) {
            throw new AwsException("FromEmailAddressNotVerified",
                    "Email address is not verified. The following identities failed the check in region "
                            + region.toUpperCase(Locale.ROOT) + ": " + template.getFromEmailAddress(), 400);
        }
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            configSetService.get(configurationSetName, region);
        }

        // AWS registers the recipient as a pending-verification identity as part of sending the
        // verification email, so ListIdentities / GetIdentityVerificationAttributes surface it.
        identityService.markPendingEmailIdentity(emailAddress, region);

        // AWS sends the template content verbatim and appends a fixed, non-removable disclaimer (SES
        // docs Q10). AWS also appends a unique verification link, which Floci does not reproduce
        // because it has no verification-click flow. The template body carries no placeholder that
        // AWS substitutes, so the content itself is passed through unchanged.
        String body = template.getTemplateContent() == null ? "" : template.getTemplateContent();
        String renderedHtml = body + "<p>" + CUSTOM_VERIFICATION_DISCLAIMER + "</p>";

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, template.getFromEmailAddress(),
                List.of(emailAddress), List.of(), List.of(), List.of(),
                template.getTemplateSubject(), null, renderedHtml);
        email.setReturnPath(template.getFromEmailAddress());
        sentEmailService.record(region, messageId, email);
        smtpRelay.relay(SmtpRelay.RelayMessage.builder(template.getFromEmailAddress())
                .returnPath(template.getFromEmailAddress())
                .to(List.of(emailAddress))
                .cc(List.of())
                .bcc(List.of())
                .replyTo(List.of())
                .subject(template.getTemplateSubject())
                .bodyHtml(renderedHtml)
                .headers(List.of())
                .messageId(messageId)
                .build());
        LOG.infov("SES custom verification email sent: to={0}, template={1}, messageId={2}",
                emailAddress, templateName, messageId);
        return messageId;
    }

    // Counts '@' characters outside a quoted local part. A valid address has exactly one — the
    // local-part/domain separator; '@' inside a quoted local part (honoring backslash escapes) is
    // part of the local part and doesn't count, so AWS-accepted forms like "a@b"@example.com pass
    // while a@@b.com and "a"@@example.com are rejected.
    private static int unquotedAtCount(String address) {
        int count = 0;
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '@' && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    /**
     * The tenant send gate (Phase 4): resolves the tenant with the send-flavored not-found wording,
     * then requires every resource the send uses to be associated with it. The tenant's
     * SendingStatus is not checked — no API can move it off ENABLED, so the DISABLED gate is not
     * emulated. Placement is probe-confirmed: request-shape validation (a missing Content, an empty
     * inline template, a missing FromEmailAddress on Simple, the bulk template-content checks) runs
     * before the tenant lookup, while the recipient checks, the raw MIME-From derivation, and
     * identity verification all lose to the tenant 404.
     */
    public void checkTenantSendAccess(String tenantName, String fromEmailAddress,
                                      String configurationSetName, String templateName,
                                      String accountId, String region) {
        if (tenantName == null) {
            return;
        }
        Tenant tenant = tenantService.tenantForSending(tenantName, region, accountId);
        String arnPrefix = "arn:aws:ses:" + region + ":" + accountId + ":";
        List<SesTenantService.AssociationResource> used = new ArrayList<>();
        if (fromEmailAddress != null && !fromEmailAddress.isBlank()) {
            String identityName = sendIdentityName(fromEmailAddress, region);
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_IDENTITY, identityName,
                    arnPrefix + "identity/" + identityName));
        }
        // The gate covers the EFFECTIVE configuration set: an omitted name resolves to the sender
        // identity's default, and that one needs the association just as an explicit one does.
        String effectiveConfigSet =
                resolveDefaultConfigurationSet(configurationSetName, fromEmailAddress, region);
        if (effectiveConfigSet != null && !effectiveConfigSet.isBlank()) {
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET, effectiveConfigSet,
                    arnPrefix + "configuration-set/" + effectiveConfigSet));
        }
        if (templateName != null && !templateName.isBlank()) {
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_TEMPLATE, templateName,
                    arnPrefix + "template/" + templateName));
        }
        tenantService.requireResourcesAssociated(tenant, used, region);
    }

    /**
     * The raw-content variant of the tenant send gate: when {@code FromEmailAddress} is omitted,
     * the effective sender comes from the MIME {@code From} header — the same derivation
     * {@code sendRawEmail} applies — so the gate must resolve it the same way before checking.
     */
    public void checkTenantRawSendAccess(String tenantName, String fromEmailAddress,
                                         String rawMessage, String configurationSetName,
                                         String accountId, String region) {
        if (tenantName == null) {
            return;
        }
        SmtpRelay.RawMessageHeaders headers = SmtpRelay.parseRawHeaders(rawMessage);
        String effectiveSource = fromEmailAddress;
        if (effectiveSource == null || effectiveSource.isBlank()) {
            effectiveSource = headers.from().isBlank() ? null : headers.from();
        }
        // The gate sees the same configuration set the send will use, so one named only by the
        // X-SES-CONFIGURATION-SET header cannot slip past the tenant association check.
        checkTenantSendAccess(tenantName, effectiveSource,
                firstNonBlank(configurationSetName, headers.configurationSet()), null,
                accountId, region);
    }

    // The gate names the identity the way AWS did in the probed error: the exact address identity
    // when one exists, otherwise the domain identity the address falls under. The sender may carry
    // display-name syntax ("Name <a@b>"), so the bare address is extracted first.
    private String sendIdentityName(String fromEmailAddress, String region) {
        String email = extractEmailAddress(fromEmailAddress);
        if (email.isBlank()) {
            return fromEmailAddress.trim();
        }
        if (identityService.find(email, region).isPresent()) {
            return email;
        }
        int at = email.lastIndexOf('@');
        if (at >= 0) {
            String domain = email.substring(at + 1);
            if (identityService.find(domain, region).isPresent()) {
                return domain;
            }
        }
        return email;
    }

    /**
     * Returns the effective suppression reasons for a send that is using
     * {@code configurationSetName}. Per the AWS V2 contract, a configuration
     * set's {@code SuppressionOptions} (when present) overrides the
     * account-level reasons — including an empty list, which explicitly
     * disables suppression filtering for that set. Falls back to the
     * account-level reasons when the configuration set has no override, or
     * when {@code configurationSetName} is null/blank (i.e. the caller didn't
     * specify a configuration set).
     */
    public List<String> getEffectiveSuppressedReasons(String configurationSetName, String region) {
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            ConfigurationSet cs = configSetService.get(configurationSetName, region);
            SuppressionOptions options = cs.getSuppressionOptions();
            if (options != null) {
                return List.copyOf(options.getSuppressedReasons());
            }
        }
        return List.copyOf(
                suppressionService.getAccountSuppressionAttributes(region).getSuppressedReasons());
    }

    /**
     * Resolve the effective suppression reason for each address in a single pass over the
     * store and the effective settings. The returned map only contains entries for
     * addresses that ARE suppressed (i.e., on the list AND whose reason matches the
     * effective {@code suppressedReasons} — the configuration set's
     * {@code SuppressionOptions} override if present, else the account-level reasons).
     * Callers reuse this map for both the SMTP relay filter and the event-publishing
     * partitioning so the store is read once per send regardless of the number of
     * consumers.
     */
    Map<String, String> collectSuppressedReasons(Collection<String> addresses,
                                                  String configurationSetName, String region) {
        if (addresses == null || addresses.isEmpty()) {
            return Map.of();
        }
        List<String> effectiveReasons = getEffectiveSuppressedReasons(configurationSetName, region);
        if (effectiveReasons == null || effectiveReasons.isEmpty()) {
            return Map.of();
        }
        Set<String> reasonFilter = Set.copyOf(effectiveReasons);
        Map<String, String> result = new LinkedHashMap<>();
        for (String address : addresses) {
            if (address == null || address.isBlank() || result.containsKey(address)) {
                continue;
            }
            SuppressedDestination entry = suppressionService.findSuppressedDestination(region, address)
                    .orElse(null);
            if (entry != null && entry.getReason() != null
                    && reasonFilter.contains(entry.getReason())) {
                result.put(address, entry.getReason());
            }
        }
        return result;
    }

    private static boolean hasListManagement(ListManagementOptions listManagement) {
        return listManagement != null && listManagement.contactListName() != null
                && !listManagement.contactListName().isBlank();
    }

    /**
     * Builds the functional one-click unsubscribe URL served by Floci's {@code /_aws/ses/unsubscribe}
     * endpoint. Unlike AWS's opaque hosted URL, the list, topic, and address are carried as readable
     * query parameters so the link is directly usable and testable against the local emulator.
     */
    private String buildUnsubscribeUrl(String region, ListManagementOptions listManagement, String address) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringBuilder sb = new StringBuilder(base)
                .append("/_aws/ses/unsubscribe?region=").append(urlEncode(region))
                .append("&contactList=").append(urlEncode(listManagement.contactListName()))
                .append("&address=").append(urlEncode(address));
        if (listManagement.topicName() != null && !listManagement.topicName().isBlank()) {
            sb.append("&topic=").append(urlEncode(listManagement.topicName()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Replaces up to the first two {@code {{amazonSESUnsubscribeUrl}}} occurrences, as AWS does. */
    private static String replaceUnsubscribePlaceholder(String body, String url) {
        if (body == null || !body.contains(UNSUBSCRIBE_PLACEHOLDER)) {
            return body;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int replaced = 0;
        while (replaced < 2) {
            int at = body.indexOf(UNSUBSCRIBE_PLACEHOLDER, from);
            if (at < 0) {
                break;
            }
            out.append(body, from, at).append(url);
            from = at + UNSUBSCRIBE_PLACEHOLDER.length();
            replaced++;
        }
        out.append(body.substring(from));
        return out.toString();
    }

    private static List<MessageHeader> withUnsubscribeHeaders(List<MessageHeader> headers, String url) {
        List<MessageHeader> out = new ArrayList<>();
        // Override any caller-supplied unsubscribe headers rather than appending a duplicate, matching
        // AWS ("SES will override these headers if they are present in the email").
        if (headers != null) {
            for (MessageHeader h : headers) {
                if (!"List-Unsubscribe".equalsIgnoreCase(h.name())
                        && !"List-Unsubscribe-Post".equalsIgnoreCase(h.name())) {
                    out.add(h);
                }
            }
        }
        out.add(new MessageHeader("List-Unsubscribe", "<" + url + ">"));
        out.add(new MessageHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        return out;
    }

    /**
     * Filter out recipients whose effective suppression reason is non-null. Returns a new
     * list containing only the addresses that should reach the SMTP relay, mirroring AWS
     * SES's "accept the message, but doesn't send it" behaviour for suppressed addresses.
     * Returns the original reference when {@code addresses} is {@code null} or empty.
     */
    static List<String> filterUnsuppressed(List<String> addresses, Map<String, String> suppressedReasons) {
        if (addresses == null || addresses.isEmpty()) {
            return addresses;
        }
        if (suppressedReasons.isEmpty()) {
            return addresses;
        }
        List<String> kept = new ArrayList<>(addresses.size());
        for (String a : addresses) {
            if (!suppressedReasons.containsKey(a)) {
                kept.add(a);
            }
        }
        return kept;
    }

    public String sendTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                     List<String> bccAddresses, List<String> replyToAddresses,
                                     String returnPath, String templateName, JsonNode templateData,
                                     String configurationSetName, List<MessageTag> emailTags,
                                     List<MessageHeader> additionalHeaders,
                                     ListManagementOptions listManagement, String region) {
        EmailTemplate template = templateService.getTemplate(templateName, region);
        return sendInlineTemplatedEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, returnPath, template.getSubject(), template.getTextPart(),
                template.getHtmlPart(), templateData,
                configurationSetName, emailTags, additionalHeaders, listManagement, region);
    }

    /**
     * Also called by the controller ahead of the tenant send gate: AWS reports an empty inline
     * template before it looks the tenant up (probe-confirmed), so the check must not stay behind
     * the gate for tenant sends.
     */
    static void requireInlineTemplateContent(String subject, String textPart, String htmlPart) {
        boolean hasSubject = subject != null && !subject.isBlank();
        boolean hasText = textPart != null && !textPart.isBlank();
        boolean hasHtml = htmlPart != null && !htmlPart.isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
    }

    public String sendInlineTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                            List<String> bccAddresses, List<String> replyToAddresses,
                                            String returnPath,
                                            String subject, String textPart, String htmlPart,
                                            JsonNode templateData,
                                            String configurationSetName, List<MessageTag> emailTags,
                                            List<MessageHeader> additionalHeaders,
                                            ListManagementOptions listManagement, String region) {
        requireInlineTemplateContent(subject, textPart, htmlPart);
        return sendEmail(source, toAddresses, ccAddresses, bccAddresses, replyToAddresses, returnPath,
                SesTemplateService.applyTemplateData(subject, templateData),
                SesTemplateService.applyTemplateData(textPart, templateData),
                SesTemplateService.applyTemplateData(htmlPart, templateData),
                configurationSetName, emailTags, additionalHeaders, listManagement, region);
    }

    public List<BulkEmailEntryResult> sendBulkTemplatedEmail(String source,
                                                              List<String> replyToAddresses,
                                                              String returnPath,
                                                              String subject, String textPart, String htmlPart,
                                                              JsonNode defaultTemplateData,
                                                              List<BulkEmailEntry> entries,
                                                              String configurationSetName,
                                                              List<MessageTag> defaultEmailTags,
                                                              List<MessageHeader> defaultHeaders,
                                                              String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        requireInlineTemplateContent(subject, textPart, htmlPart);
        if (entries == null || entries.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination entry is required.", 400);
        }
        configSetService.validateForSending(configurationSetName, region);
        if (entries.size() > MAX_BULK_DESTINATIONS) {
            throw new AwsException("MessageRejected",
                    "Number of destinations (" + entries.size() + ") exceeds the maximum of "
                            + MAX_BULK_DESTINATIONS + ".", 400);
        }
        for (BulkEmailEntry entry : entries) {
            int recipientCount = sizeOf(entry.toAddresses())
                    + sizeOf(entry.ccAddresses())
                    + sizeOf(entry.bccAddresses());
            if (recipientCount > MAX_RECIPIENTS_PER_DESTINATION) {
                throw new AwsException("MessageRejected",
                        "Recipient count (" + recipientCount + ") in a destination exceeds the maximum of "
                                + MAX_RECIPIENTS_PER_DESTINATION + ".", 400);
            }
        }

        List<BulkEmailEntryResult> results = new ArrayList<>(entries.size());
        for (BulkEmailEntry entry : entries) {
            try {
                JsonNode merged = mergeTemplateData(defaultTemplateData, entry.replacementTemplateData());
                List<MessageTag> mergedTags = mergeEmailTags(defaultEmailTags, entry.replacementEmailTags());
                List<MessageHeader> mergedHeaders = mergeHeaders(defaultHeaders, entry.replacementHeaders());
                // SendBulkEmail has no ListManagementOptions field, so list-managed suppression
                // does not apply to bulk sends.
                String messageId = sendEmail(source,
                        entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                        replyToAddresses, returnPath,
                        SesTemplateService.applyTemplateData(subject, merged),
                        SesTemplateService.applyTemplateData(textPart, merged),
                        SesTemplateService.applyTemplateData(htmlPart, merged),
                        configurationSetName, mergedTags, mergedHeaders, null, region);
                results.add(BulkEmailEntryResult.success(messageId));
            } catch (AwsException e) {
                results.add(BulkEmailEntryResult.failure(
                        mapErrorCodeToBulkStatus(e.getErrorCode()), e.getMessage()));
            } catch (Exception e) {
                results.add(BulkEmailEntryResult.failure(BulkEmailEntryResult.Status.FAILED, e.getMessage()));
            }
        }
        return results;
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static BulkEmailEntryResult.Status mapErrorCodeToBulkStatus(String errorCode) {
        if ("InvalidParameterValue".equals(errorCode)
                || "MissingRenderingAttribute".equals(errorCode)
                || "InvalidRenderingParameter".equals(errorCode)) {
            return BulkEmailEntryResult.Status.INVALID_PARAMETER;
        }
        return BulkEmailEntryResult.Status.FAILED;
    }

    static List<MessageHeader> mergeHeaders(List<MessageHeader> defaults, List<MessageHeader> replacement) {
        boolean hasDefault = defaults != null && !defaults.isEmpty();
        boolean hasReplacement = replacement != null && !replacement.isEmpty();
        if (!hasDefault && !hasReplacement) {
            return List.of();
        }
        // RFC 5322 header field names are case-insensitive, so the merge key is the
        // lowercased name. The header itself is stored verbatim, so the replacement's
        // original casing wins when it overrides a default.
        LinkedHashMap<String, MessageHeader> byLowerName = new LinkedHashMap<>();
        if (hasDefault) {
            for (MessageHeader h : defaults) {
                if (h != null && h.name() != null && !h.name().isBlank()) {
                    byLowerName.put(h.name().toLowerCase(Locale.ROOT), h);
                }
            }
        }
        if (hasReplacement) {
            for (MessageHeader h : replacement) {
                if (h != null && h.name() != null && !h.name().isBlank()) {
                    byLowerName.put(h.name().toLowerCase(Locale.ROOT), h);
                }
            }
        }
        return new ArrayList<>(byLowerName.values());
    }

    static List<MessageTag> mergeEmailTags(List<MessageTag> defaults, List<MessageTag> replacement) {
        boolean hasDefault = defaults != null && !defaults.isEmpty();
        boolean hasReplacement = replacement != null && !replacement.isEmpty();
        if (!hasDefault && !hasReplacement) {
            return List.of();
        }
        LinkedHashMap<String, MessageTag> byName = new LinkedHashMap<>();
        if (hasDefault) {
            for (MessageTag t : defaults) {
                if (t != null && t.name() != null && !t.name().isBlank()) {
                    byName.put(t.name(), t);
                }
            }
        }
        if (hasReplacement) {
            for (MessageTag t : replacement) {
                if (t != null && t.name() != null && !t.name().isBlank()) {
                    byName.put(t.name(), t);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    static JsonNode mergeTemplateData(JsonNode defaults, JsonNode replacement) {
        boolean hasDefault = defaults != null && defaults.isObject();
        boolean hasReplacement = replacement != null && replacement.isObject();
        if (!hasDefault && !hasReplacement) {
            return null;
        }
        if (!hasReplacement) {
            return defaults;
        }
        if (!hasDefault) {
            return replacement;
        }
        if (replacement.isEmpty()) {
            return defaults;
        }
        if (defaults.isEmpty()) {
            return replacement;
        }
        ObjectNode merged = ((ObjectNode) defaults).deepCopy();
        replacement.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        return merged;
    }

    /**
     * Resolve the suppression reason that applies to a given recipient in the given region
     * for sends using {@code configurationSetName}, or {@code null} if the recipient is not
     * suppressed. The recipient is suppressed only when it appears in the address-level
     * suppression list AND its stored reason intersects the effective {@code suppressedReasons}
     * — the configuration set's {@code SuppressionOptions} override if present, else the
     * account-level reasons. {@code configurationSetName} may be {@code null} or blank to
     * scope the check to account-level reasons only.
     *
     * <p>The returned value is one of {@code "BOUNCE"} / {@code "COMPLAINT"}, allowing
     * callers (publishSendEvents) to map the recipient to a synthetic Bounce / Complaint
     * event without consulting the store again. Both the per-address suppression entries
     * and the account-level / per-CS {@code suppressedReasons} go through reason validation
     * (in {@link SesSuppressionService} and {@link SesConfigurationSetService} respectively),
     * which enforces exact case-sensitive equality with the two canonical values, so
     * {@code entry.getReason()} is guaranteed to be canonical and downstream
     * {@code .equals("BOUNCE")} / {@code .equals("COMPLAINT")} checks are safe.
     */
    String resolveSuppressionReason(String emailAddress, String configurationSetName, String region) {
        if (emailAddress == null || emailAddress.isBlank()) {
            return null;
        }
        // Read through the suppression service so this shares its normalization and legacy-key
        // fallback with GET/DELETE (lookups can't drift apart from inserts).
        SuppressedDestination entry = suppressionService.findSuppressedDestination(region, emailAddress)
                .orElse(null);
        if (entry == null || entry.getReason() == null) {
            return null;
        }
        List<String> effective = getEffectiveSuppressedReasons(configurationSetName, region);
        if (effective == null || effective.isEmpty()) {
            return null;
        }
        return effective.contains(entry.getReason()) ? entry.getReason() : null;
    }
}
