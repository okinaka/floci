package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test signature is obtained from {@link SesContentScan#signature()} and never spelled out
 * here, so this source file is as safe to check out as the production one.
 */
class SesContentScanTest {

    private static final String SIGNATURE = SesContentScan.signature();

    @Test
    void assembledSignatureHasTheDocumentedLength() {
        assertEquals(68, SIGNATURE.length());
    }

    @Test
    void cleanTextIsNotFlagged() {
        assertFalse(SesContentScan.containsTestVirus("hello", "<p>hello</p>", null));
    }

    @Test
    void signatureInsideATextBodyIsFlagged() {
        assertTrue(SesContentScan.containsTestVirus(null, "prefix " + SIGNATURE + " suffix"));
    }

    @Test
    void signatureInsideAPlainMimeBodyIsFlagged() {
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n\r\n" + SIGNATURE + "\r\n";

        assertTrue(SesContentScan.containsTestVirus(mime.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureInsideAQuotedPrintableAttachmentIsFoundOnlyByDecoding() {
        // Quoted-printable with escaped characters: neither the plain signature nor its base64
        // spelling is on the wire, so only the decoded part walk can find it.
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nsee attachment\r\n"
                + "--b\r\nContent-Type: application/octet-stream; name=\"eicar.com\"\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "Content-Disposition: attachment; filename=\"eicar.com\"\r\n\r\n"
                + quotedPrintable(SIGNATURE) + "\r\n--b--\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertTrue(SesContentScan.containsTestVirus(bytes));
    }

    @Test
    void signatureStraddlingAChunkBoundaryInsideALargeAttachmentIsFlagged() {
        // The decoded part is longer than one scan chunk and the signature starts 30 bytes
        // before the boundary, so only the carried-over overlap can complete the match. Quoted-
        // printable keeps the signature off the wire, so the match has to come from the stream.
        String decoded = "a".repeat(SesContentScan.CHUNK_SIZE - 30) + SIGNATURE + "a".repeat(4096);
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                + quotedPrintable(decoded) + "\r\n--b--\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertTrue(SesContentScan.containsTestVirus(bytes));
    }

    @Test
    void streamScanFindsASignatureStraddlingTheChunkBoundary() throws IOException {
        // A ByteArrayInputStream hands back exactly the requested chunk, so the signature that
        // starts 30 bytes before CHUNK_SIZE is split across two reads and only the carried-over
        // overlap can complete the match. The MIME test above cannot pin this down because the
        // decoder's own read sizes do not line up with the scan chunk.
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        int prefix = SesContentScan.CHUNK_SIZE - 30;
        byte[] data = new byte[prefix + signature.length + 10];
        Arrays.fill(data, (byte) 'a');
        System.arraycopy(signature, 0, data, prefix, signature.length);

        assertTrue(SesContentScan.streamContains(new ByteArrayInputStream(data), signature));
        assertFalse(SesContentScan.streamContains(new ByteArrayInputStream(new byte[prefix + 200]), signature));
    }

    @Test
    void signatureInsideAForwardedMessageIsFlagged() {
        // message/rfc822 parts surface as an embedded Message body, not a leaf; the signature sits
        // in a quoted-printable attachment of the forwarded message, two levels down, so only the
        // walk into the embedded message can find it.
        String inner = "From: c@example.com\r\nTo: d@example.com\r\nSubject: fwd\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"i\"\r\n\r\n"
                + "--i\r\nContent-Type: text/plain\r\n\r\nforwarded\r\n"
                + "--i\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                + quotedPrintable(SIGNATURE) + "\r\n--i--\r\n";
        String outer = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"o\"\r\n\r\n"
                + "--o\r\nContent-Type: text/plain\r\n\r\nsee forwarded message\r\n"
                + "--o\r\nContent-Type: message/rfc822\r\n\r\n" + inner + "\r\n--o--\r\n";
        byte[] bytes = outer.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertTrue(SesContentScan.containsTestVirus(bytes));
    }

    @Test
    void signatureInsideAnEncodedWordSubjectIsFoundOnlyByDecodingTheHeader() {
        // An RFC 2047 encoded word carries at most 75 characters, so the signature spans two words
        // and each restarts the base64 alignment: neither the plain nor the base64 wire scan sees it.
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        String first = Base64.getEncoder().encodeToString(Arrays.copyOfRange(signature, 0, 45));
        String second = Base64.getEncoder().encodeToString(Arrays.copyOfRange(signature, 45, signature.length));
        String mime = "From: a@example.com\r\nTo: b@example.com\r\n"
                + "Subject: =?UTF-8?B?" + first + "?=\r\n =?UTF-8?B?" + second + "?=\r\n\r\nclean\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertTrue(SesContentScan.containsTestVirus(bytes));
    }

    @Test
    void base64SignatureIsFoundOnTheWireInEveryAlignmentEvenWhenTheParserSwallowsIt() {
        // No headers and no blank line: the lenient parser reads the whole blob as header lines,
        // so nothing is decoded and only the on-the-wire base64 match can catch it. Each prefix
        // length shifts the encoding to a different alignment, and the MIME encoder wraps at 76
        // characters, so the match must also skip line breaks.
        for (int prefix = 0; prefix < 3; prefix++) {
            byte[] payload = new byte[prefix + SIGNATURE.length() + 5];
            Arrays.fill(payload, (byte) 'q');
            System.arraycopy(SIGNATURE.getBytes(StandardCharsets.US_ASCII), 0, payload, prefix, SIGNATURE.length());
            byte[] blob = Base64.getMimeEncoder().encode(payload);

            assertFalse(new String(blob, StandardCharsets.ISO_8859_1).contains(SIGNATURE));
            assertTrue(SesContentScan.containsTestVirus(blob), "alignment offset " + prefix);
        }
    }

    @Test
    void base64OfANearMissIsNotFlaggedOnTheWire() {
        // The needles cover only the whole base64 groups inside the signature, so a prefix or a
        // suffix of it that keeps those groups still matches a needle; the decoded run must then
        // show the missing edge bytes are not there.
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        byte[] prefix = Arrays.copyOf(signature, signature.length);
        prefix[66] = 'Z';
        prefix[67] = 'Z';
        byte[] suffix = ("ZZZ" + SIGNATURE.substring(1, 67)).getBytes(StandardCharsets.US_ASCII);
        for (byte[] nearMiss : new byte[][] {prefix, suffix}) {
            String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                    + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                    + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                    + Base64.getMimeEncoder().encodeToString(nearMiss) + "\r\n--b--\r\n";

            assertFalse(SesContentScan.containsTestVirus(mime.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void longRunOfLineBreaksIsScannedInLinearTime() {
        // Every line break used to start its own scan that skipped the rest of the run, which is
        // quadratic; 200k of them would take minutes rather than milliseconds.
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n\r\nQ"
                + "\r\n".repeat(100_000) + "clean\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> SesContentScan.containsTestVirus(bytes)));
    }

    @Test
    void base64OfCleanContentIsNotFlagged() {
        byte[] blob = Base64.getMimeEncoder().encode("nothing to see here, just a long enough clean payload".getBytes(StandardCharsets.US_ASCII));

        assertFalse(SesContentScan.containsTestVirus(blob));
    }

    @Test
    void eachBase64AlignmentNeedleIsFullyDeterminedBySignatureAlone() {
        byte[][] needles = SesContentScan.base64Alignments(SIGNATURE.getBytes(StandardCharsets.US_ASCII));

        assertEquals(3, needles.length);
        for (byte[] needle : needles) {
            assertEquals(88, needle.length);
        }
    }

    @Test
    void cleanMimeWithAnAttachmentIsNotFlagged() {
        String encoded = Base64.getEncoder().encodeToString("just a file".getBytes(StandardCharsets.US_ASCII));
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nhello\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + encoded + "\r\n--b--\r\n";

        assertFalse(SesContentScan.containsTestVirus(mime.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bytesThatAreNotMimeFallBackToARawScan() {
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[signature.length + 2];
        bytes[0] = 0;
        bytes[1] = 1;
        System.arraycopy(signature, 0, bytes, 2, signature.length);

        assertTrue(SesContentScan.containsTestVirus(bytes));
    }

    @Test
    void emptyInputIsNotFlagged() {
        assertFalse(SesContentScan.containsTestVirus(new byte[0]));
        assertFalse(SesContentScan.containsTestVirus((byte[]) null));
    }

    // Escapes the characters of the signature that quoted-printable may encode, and soft-wraps,
    // so the wire carries neither the plain signature nor a base64 spelling of it.
    private static String quotedPrintable(String decoded) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < decoded.length(); i += 60) {
            if (i > 0) {
                out.append("=\r\n");
            }
            out.append(decoded.substring(i, Math.min(decoded.length(), i + 60))
                    .replace("!", "=21").replace("$", "=24"));
        }
        return out.toString();
    }
}
