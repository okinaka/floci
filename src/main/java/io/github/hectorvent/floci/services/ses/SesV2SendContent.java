package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

import static io.github.hectorvent.floci.services.ses.SesV2Json.typedBlobMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.typedStringMemberOrAbsent;

/**
 * The scalar members of a v2 send's content, read with their wire types and nothing else. AWS
 * fails a member of the wrong JSON type during deserialization, ahead of every check of the
 * request (probe-confirmed), so the send controller reads all of them through here before it
 * validates anything, whichever content type the request carries. An absent member is null.
 */
final class SesV2SendContent {

    record Raw(String data) {
    }

    record Simple(String subject, String text, String html) {
    }

    record Template(String name, String arn, String inlineSubject, String inlineText, String inlineHtml) {
    }

    record Members(Raw raw, Simple simple, Template template) {
    }

    private SesV2SendContent() {
    }

    /** The members of a {@code SendEmail} {@code Content}. */
    static Members read(JsonNode content) {
        return new Members(readRaw(content.path("Raw")), readSimple(content.path("Simple")),
                readTemplate(content.path("Template")));
    }

    /**
     * The members of a {@code Template}: {@code Content.Template} of {@code SendEmail} or
     * {@code DefaultContent.Template} of {@code SendBulkEmail}.
     */
    static Template readTemplate(JsonNode template) {
        JsonNode inline = template.path("TemplateContent");
        return new Template(typedStringMemberOrAbsent(template, "TemplateName"),
                typedStringMemberOrAbsent(template, "TemplateArn"),
                typedStringMemberOrAbsent(inline, "Subject"),
                typedStringMemberOrAbsent(inline, "Text"),
                typedStringMemberOrAbsent(inline, "Html"));
    }

    private static Raw readRaw(JsonNode raw) {
        return new Raw(typedBlobMemberOrAbsent(raw, "Data"));
    }

    // A Simple subject is sent even when empty, so an absent one reads as "".
    private static Simple readSimple(JsonNode simple) {
        return new Simple(Objects.requireNonNullElse(typedStringMemberOrAbsent(simple.path("Subject"), "Data"), ""),
                typedStringMemberOrAbsent(simple.path("Body").path("Text"), "Data"),
                typedStringMemberOrAbsent(simple.path("Body").path("Html"), "Data"));
    }
}
