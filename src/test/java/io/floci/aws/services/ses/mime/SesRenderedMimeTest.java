package io.floci.aws.services.ses.mime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesRenderedMimeTest {

    @Test
    void buildTestRenderMime_asciiBody_uses7bit() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("Hello", "Hi there", "<p>Hi</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: Hello\r\n"));
        assertTrue(mime.startsWith("Date: 2026-05-02T12:00:00.000Z\r\n"));
        assertTrue(mime.contains("Content-Type: multipart/alternative; \r\n\tboundary=\"BOUND\"\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit\r\n\r\nHi there\r\n--BOUND\r\n"));
        assertFalse(mime.contains("Content-Transfer-Encoding: 8bit"));
        assertTrue(mime.endsWith("--BOUND--\r\n"));
    }

    @Test
    void buildTestRenderMime_mostlyNonAsciiBody_usesBase64AndAnEncodedSubject() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("件名", "こんにちは", "<p>こんにちは</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: =?UTF-8?B?5Lu25ZCN?=\r\n"));
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n44GT44KT44Gr44Gh44Gv\r\n--BOUND\r\n"));
        assertFalse(mime.contains("こんにちは"));
    }

    @Test
    void buildTestRenderMime_mostlyAsciiBody_usesQuotedPrintablePerPart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "plain text", "<p>café</p>", date, "BOUND");
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit\r\n\r\nplain text\r\n--BOUND\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: quoted-printable\r\n\r\n<p>caf=C3=A9</p>\r\n--BOUND--\r\n"));
    }

    @Test
    void buildTestRenderMime_subjectStripsCRLF() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("Multi\r\nLine", "x", "x", date, "BOUND");
        assertTrue(mime.contains("Subject: Multi Line\r\n"));
    }

    @Test
    void buildTestRenderMime_sevenBitBodyKeepsItsBareLf() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "line1\nline2", "<p>x\ny</p>", date, "BOUND");
        assertTrue(mime.contains("\r\n\r\nline1\nline2\r\n--BOUND\r\n"));
        assertTrue(mime.contains("\r\n\r\n<p>x\ny</p>\r\n--BOUND--\r\n"));
    }

    // Not probed directly: it follows from the CRLF that opens every boundary line, which the
    // base64 case below shows is written whatever the part ends with.
    @Test
    void buildTestRenderMime_bodyEndingWithNewlineKeepsItBeforeTheBoundary() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "hello\n", "<p>hi</p>\r\n", date, "BOUND");
        assertTrue(mime.contains("\r\n\r\nhello\n\r\n--BOUND\r\n"));
        assertTrue(mime.contains("\r\n\r\n<p>hi</p>\r\n\r\n--BOUND--\r\n"));
    }

    // probed: a base64 part that fills its last line ends in CRLF, so a blank line precedes the boundary
    @Test
    void buildTestRenderMime_base64PartFillingItsLastLineIsFollowedByABlankLine() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "あ".repeat(19), "<p>x</p>", date, "BOUND");
        assertTrue(mime.contains("Content-Transfer-Encoding: base64\r\n\r\n"
                + "44GC".repeat(19) + "\r\n\r\n--BOUND\r\n"));
    }

    // probed: a template with one part is not multipart, and nothing follows its body
    @Test
    void buildTestRenderMime_textOnlyTemplateIsASinglePart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "hello x\n", null, date, "BOUND");
        assertEquals("Date: 2026-05-02T12:00:00.000Z\r\nSubject: S\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nhello x\n", mime);
    }

    @Test
    void buildTestRenderMime_htmlOnlyTemplateIsASinglePart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", null, "<p>日本語のテスト</p>\r\n", date, "BOUND");
        assertEquals("Date: 2026-05-02T12:00:00.000Z\r\nSubject: S\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + "PHA+5pel5pys6Kqe44Gu44OG44K544OIPC9wPg0K", mime);
    }

    @Test
    void buildTestRenderMime_bodyWithoutTrailingNewline_addsCrlfBeforeBoundary() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("S", "hello", "<p>hi</p>", date, "BOUND");
        assertTrue(mime.contains("hello\r\n--BOUND"));
        assertTrue(mime.contains("</p>\r\n--BOUND"));
    }

    @Test
    void sanitizeSubject_nullReturnsEmpty() {
        assertEquals("", SesRenderedMime.sanitizeSubject(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sanitizeSubjectCases")
    void sanitizeSubject_returnsExpected(String label, String input, String expected) {
        assertEquals(expected, SesRenderedMime.sanitizeSubject(input));
    }

    // probed, each row
    static Stream<Arguments> sanitizeSubjectCases() {
        return Stream.of(
                Arguments.of("LF",                        "line1\nline2",   "line1 line2"),
                Arguments.of("CRLF is one space",         "a\r\nb",        "a b"),
                Arguments.of("space, LF and tab",         "x \nb\tc",      "x b c"),
                Arguments.of("two line breaks",           "a\n\nb",        "a b"),
                Arguments.of("plain spaces collapse too", "a  b",           "a b"),
                Arguments.of("other controls are kept",   "a\u0001b",       "a\u0001b"),
                Arguments.of("Unicode preserved",         "Hello 太郎",       "Hello 太郎")
        );
    }

    @Test
    void buildTestRenderMime_subjectControlCharacterIsEncoded() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesRenderedMime.buildTestRenderMime("a\u0001b", "x", "x", date, "BOUND");
        // probed
        assertTrue(mime.contains("Subject: =?UTF-8?Q?a=01b?=\r\n"));
    }

    // The v1 response carries the message as XML character data and has no sanitizing step, so
    // nothing outside printable ASCII, tab, CR and LF may ever reach the rendered message.
    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileContentCases")
    void buildTestRenderMime_isAlwaysXmlSafeAscii(String label, String value) {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        List<String> rendered = List.of(
                SesRenderedMime.buildTestRenderMime(value, value, "<p>" + value + "</p>", date, "BOUND"),
                SesRenderedMime.buildTestRenderMime(value, value, null, date, "BOUND"),
                SesRenderedMime.buildTestRenderMime(value, null, "<p>" + value + "</p>", date, "BOUND"));
        for (String mime : rendered) {
            for (int i = 0; i < mime.length(); i++) {
                char c = mime.charAt(i);
                assertTrue(c == '\t' || c == '\r' || c == '\n' || (c >= 0x20 && c < 0x7F),
                        "unexpected U+" + Integer.toHexString(c) + " at " + i + " in " + label);
            }
        }
    }

    static Stream<Arguments> hostileContentCases() {
        return Stream.of(
                Arguments.of("C0 control", "a\u0001b"),
                Arguments.of("every C0 control and DEL", allC0ControlsAndDel()),
                Arguments.of("controls in the majority", "\u0001\u0002\u0003a"),
                Arguments.of("NUL", "a\u0000b"),
                Arguments.of("non-ASCII", "こんにちは"),
                Arguments.of("mostly ASCII with non-ASCII", "hello café"),
                Arguments.of("emoji", "ok 😀"),
                Arguments.of("lone high surrogate", "a\uD83Db"),
                Arguments.of("lone low surrogate", "a\uDE00b"),
                Arguments.of("U+FFFE and U+FFFF", "a\uFFFE\uFFFFb"),
                Arguments.of("C1 control", "a\u0085b"),
                Arguments.of("long line with a control", "a".repeat(1200) + "\u0001")
        );
    }

    private static String allC0ControlsAndDel() {
        StringBuilder out = new StringBuilder("text ");
        for (char c = 0; c < 0x20; c++) {
            out.append(c);
        }
        return out.append('\u007F').append(" text").toString();
    }
}
