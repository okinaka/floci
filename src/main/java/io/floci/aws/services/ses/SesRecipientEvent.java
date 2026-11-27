package io.floci.aws.services.ses;

import java.util.List;

/**
 * One event to publish for a send: the SES event type, why it happened, and the recipients it
 * concerns. A send yields one of these per (type, cause) pair, so a message that bounces for two
 * different reasons publishes two {@code BOUNCE} events, as SES does.
 */
record SesRecipientEvent(String eventType, Cause cause, List<String> recipients, RenderingFailure failure) {

    /**
     * Why a templated send could not be rendered. {@code templateName} is the stored template's
     * name and null for inline template content.
     */
    record RenderingFailure(String errorMessage, String templateName) {
    }

    enum Cause {
        /** A mailbox-simulator address, or the plain delivery of an ordinary recipient. */
        SIMULATOR,
        /** The recipient was on the account-level suppression list and never left Floci. */
        ACCOUNT_SUPPRESSION,
        /** The recipient had opted out through list management and never left Floci. */
        LIST_MANAGEMENT,
        /** The whole message failed the content scan and was rejected after acceptance. */
        CONTENT_REJECTED,
        /** The template data did not cover the template's variables, so no message was built. */
        RENDERING_FAILED
    }

    static SesRecipientEvent of(String eventType, Cause cause, List<String> recipients) {
        return new SesRecipientEvent(eventType, cause, List.copyOf(recipients), null);
    }

    static SesRecipientEvent renderingFailure(RenderingFailure failure) {
        return new SesRecipientEvent("RENDERING_FAILURE", Cause.RENDERING_FAILED, List.of(), failure);
    }
}
