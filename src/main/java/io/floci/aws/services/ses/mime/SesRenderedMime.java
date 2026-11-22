package io.floci.aws.services.ses.mime;

import io.floci.aws.services.ses.mime.SesMimeEncoding.TransferEncoding;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** The MIME message {@code TestRenderTemplate} returns for a rendered template. */
public final class SesRenderedMime {

    private static final DateTimeFormatter RENDER_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern SUBJECT_WHITESPACE_RUN = Pattern.compile("[ \\t\\r\\n]+");

    private SesRenderedMime() {
    }

    /**
     * A template with both a text and an HTML part renders as multipart/alternative. One with a
     * single part renders as that part alone, with nothing after its encoded body. A null part
     * is absent; an empty or blank one is written as it is.
     */
    public static String buildTestRenderMime(String subject, String text, String html,
                                             Instant date, String boundary) {
        boolean hasText = text != null;
        boolean hasHtml = html != null;
        StringBuilder out = new StringBuilder();
        out.append("Date: ").append(RENDER_DATE.format(date)).append("\r\n");
        out.append("Subject: ").append(SesMimeEncoding.encodeSubject(sanitizeSubject(subject))).append("\r\n");
        out.append("MIME-Version: 1.0\r\n");
        if (hasText && hasHtml) {
            out.append("Content-Type: multipart/alternative; \r\n\tboundary=\"").append(boundary).append("\"\r\n");
            out.append("\r\n");
            out.append("--").append(boundary).append("\r\n");
            appendEntity(out, "text/plain", text);
            out.append("\r\n--").append(boundary).append("\r\n");
            appendEntity(out, "text/html", html);
            out.append("\r\n--").append(boundary).append("--\r\n");
        } else if (hasHtml) {
            appendEntity(out, "text/html", html);
        } else {
            appendEntity(out, "text/plain", text == null ? "" : text);
        }
        return out.toString();
    }

    private static void appendEntity(StringBuilder out, String mimeType, String body) {
        byte[] octets = body.getBytes(StandardCharsets.UTF_8);
        TransferEncoding transferEncoding = TransferEncoding.of(octets);
        out.append("Content-Type: ").append(mimeType).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: ").append(transferEncoding.headerValue()).append("\r\n");
        out.append("\r\n");
        out.append(transferEncoding.encode(octets));
    }

    static String sanitizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        // SES writes every run of spaces, tabs and line breaks in the Subject as one space, so a
        // value cannot break the header. Any other control character stays and is written inside
        // an encoded word.
        return SUBJECT_WHITESPACE_RUN.matcher(subject).replaceAll(" ");
    }
}
