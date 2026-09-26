package io.github.hectorvent.floci.services.ses.model;

import java.util.List;

/**
 * The service-level input of a structured send, built by the v1 Query handler, the v2 REST
 * controller and Cognito alike, and by SesService's templated and bulk paths, which derive each
 * bulk entry from a shared base with {@link #toBuilder()}. It is not a wire shape: each caller
 * translates its own request into these fields (v2 {@code FromEmailAddress} into {@code source},
 * {@code FeedbackForwardingEmailAddress} into {@code returnPath}, v1 {@code Tags} into
 * {@code emailTags}), and a field a caller's API lacks keeps the builder default.
 */
public record SendEmailRequest(
        String source,
        List<String> toAddresses,
        List<String> ccAddresses,
        List<String> bccAddresses,
        List<String> replyToAddresses,
        String returnPath,
        String subject,
        String bodyText,
        String bodyHtml,
        String configurationSetName,
        List<MessageTag> emailTags,
        List<MessageHeader> additionalHeaders,
        ListManagementOptions listManagement,
        String tenantName,
        String region) {

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .source(source)
                .toAddresses(toAddresses)
                .ccAddresses(ccAddresses)
                .bccAddresses(bccAddresses)
                .replyToAddresses(replyToAddresses)
                .returnPath(returnPath)
                .subject(subject)
                .bodyText(bodyText)
                .bodyHtml(bodyHtml)
                .configurationSetName(configurationSetName)
                .emailTags(emailTags)
                .additionalHeaders(additionalHeaders)
                .listManagement(listManagement)
                .tenantName(tenantName)
                .region(region);
    }

    public static final class Builder {
        private String source;
        private List<String> toAddresses = List.of();
        private List<String> ccAddresses = List.of();
        private List<String> bccAddresses = List.of();
        private List<String> replyToAddresses = List.of();
        private String returnPath;
        private String subject;
        private String bodyText;
        private String bodyHtml;
        private String configurationSetName;
        private List<MessageTag> emailTags = List.of();
        private List<MessageHeader> additionalHeaders = List.of();
        private ListManagementOptions listManagement;
        private String tenantName;
        private String region;

        private Builder() {
        }

        public Builder source(String source) { this.source = source; return this; }
        public Builder toAddresses(List<String> toAddresses) { this.toAddresses = toAddresses; return this; }
        public Builder ccAddresses(List<String> ccAddresses) { this.ccAddresses = ccAddresses; return this; }
        public Builder bccAddresses(List<String> bccAddresses) { this.bccAddresses = bccAddresses; return this; }
        public Builder replyToAddresses(List<String> replyToAddresses) { this.replyToAddresses = replyToAddresses; return this; }
        public Builder returnPath(String returnPath) { this.returnPath = returnPath; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder bodyText(String bodyText) { this.bodyText = bodyText; return this; }
        public Builder bodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; return this; }
        public Builder configurationSetName(String configurationSetName) { this.configurationSetName = configurationSetName; return this; }
        public Builder emailTags(List<MessageTag> emailTags) { this.emailTags = emailTags; return this; }
        public Builder additionalHeaders(List<MessageHeader> additionalHeaders) { this.additionalHeaders = additionalHeaders; return this; }
        public Builder listManagement(ListManagementOptions listManagement) { this.listManagement = listManagement; return this; }
        public Builder tenantName(String tenantName) { this.tenantName = tenantName; return this; }
        public Builder region(String region) { this.region = region; return this; }

        public SendEmailRequest build() {
            return new SendEmailRequest(source, toAddresses, ccAddresses, bccAddresses, replyToAddresses,
                    returnPath, subject, bodyText, bodyHtml, configurationSetName, emailTags,
                    additionalHeaders, listManagement, tenantName, region);
        }
    }
}
