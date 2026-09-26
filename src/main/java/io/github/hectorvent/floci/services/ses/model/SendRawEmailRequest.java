package io.github.hectorvent.floci.services.ses.model;

import java.util.List;

/**
 * The service-level input of a raw MIME send, built by the v1 {@code SendRawEmail} handler and
 * by the v2 {@code SendEmail} controller for {@code Content.Raw}. Like {@link SendEmailRequest}
 * it is not a wire shape; a field a caller's API lacks keeps the builder default.
 */
public record SendRawEmailRequest(
        String source,
        List<String> destinations,
        String rawMessage,
        String returnPath,
        String configurationSetName,
        List<MessageTag> emailTags,
        ListManagementOptions listManagement,
        String tenantName,
        String region) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String source;
        private List<String> destinations = List.of();
        private String rawMessage;
        private String returnPath;
        private String configurationSetName;
        private List<MessageTag> emailTags = List.of();
        private ListManagementOptions listManagement;
        private String tenantName;
        private String region;

        private Builder() {
        }

        public Builder source(String source) { this.source = source; return this; }
        public Builder destinations(List<String> destinations) { this.destinations = destinations; return this; }
        public Builder rawMessage(String rawMessage) { this.rawMessage = rawMessage; return this; }
        public Builder returnPath(String returnPath) { this.returnPath = returnPath; return this; }
        public Builder configurationSetName(String configurationSetName) { this.configurationSetName = configurationSetName; return this; }
        public Builder emailTags(List<MessageTag> emailTags) { this.emailTags = emailTags; return this; }
        public Builder listManagement(ListManagementOptions listManagement) { this.listManagement = listManagement; return this; }
        public Builder tenantName(String tenantName) { this.tenantName = tenantName; return this; }
        public Builder region(String region) { this.region = region; return this; }

        public SendRawEmailRequest build() {
            return new SendRawEmailRequest(source, destinations, rawMessage, returnPath,
                    configurationSetName, emailTags, listManagement, tenantName, region);
        }
    }
}
