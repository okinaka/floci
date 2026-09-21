package io.github.hectorvent.floci.services.ses;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.stream.Field;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The content scan SES runs on every accepted message. Real SES scans for viruses and rejects the
 * whole message with a {@code Reject} event; Floci reproduces that for the one input AWS documents
 * for testing it, the EICAR test file, and nothing else.
 *
 * <p>The EICAR string is a signature that endpoint protection quarantines on sight, so it is never
 * written out verbatim: it is assembled from fragments at class initialisation, must never be
 * logged, and is exposed to tests only through {@link #signature()} so no test source carries it
 * either. A message that trips the scan is likewise never persisted with its body.
 */
final class SesContentScan {

    private static final Logger LOG = Logger.getLogger(SesContentScan.class);

    // The fragments only mean something once joined, which keeps this source file below the
    // detection threshold of the scanners the joined string is designed to trigger.
    private static final String[] FRAGMENTS = {
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
            "EICAR-STANDARD-",
            "ANTIVIRUS-TEST-",
            "FILE!$H+H*"
    };
    private static final String SIGNATURE_TEXT = String.join("", FRAGMENTS);
    private static final byte[] SIGNATURE = SIGNATURE_TEXT.getBytes(StandardCharsets.US_ASCII);
    // The base64 spelling of the signature has three possible alignments, and in each the groups
    // that lie fully inside the signature are fixed, so an encoded copy can be located on the wire
    // without decoding, whatever the lenient MIME parser made of the surrounding bytes; the run
    // around a candidate is then decoded to confirm all of the signature, edge bytes included.
    private static final byte[][] BASE64_SIGNATURES = base64Alignments(SIGNATURE);

    // Decoded parts are read as a stream in chunks of this size with a rolling overlap, so the
    // scan itself never needs a whole part in one array, whatever the parser buffers.
    static final int CHUNK_SIZE = 64 * 1024;

    private SesContentScan() {}

    /** The assembled test string, for tests that need to send it. Never log or persist it. */
    static String signature() {
        return SIGNATURE_TEXT;
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(String... texts) {
        return containsTestVirus(Arrays.asList(texts));
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(Iterable<String> texts) {
        for (String text : texts) {
            if (text != null && text.contains(SIGNATURE_TEXT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the message carries the test signature: plain or base64-encoded in the wire
     * bytes, or inside a decoded MIME part such as an attachment or a forwarded message.
     */
    static boolean containsTestVirus(byte[] mime) {
        if (mime == null || mime.length == 0) {
            return false;
        }
        // The wire bytes are scanned first, plain and base64-encoded: a lenient MIME parse can
        // swallow a headerless payload as malformed header lines, in which case the walk below
        // never sees its content. Line breaks are skipped so a wrapped base64 body still matches.
        if (indexOf(mime, SIGNATURE) >= 0) {
            return true;
        }
        for (byte[] encoded : BASE64_SIGNATURES) {
            int at = indexOfIgnoringLineBreaks(mime, encoded, 0);
            while (at >= 0) {
                if (base64RunContainsSignature(mime, at)) {
                    return true;
                }
                at = indexOfIgnoringLineBreaks(mime, encoded, at + 1);
            }
        }
        try {
            Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(mime));
            return entityContains(message);
        } catch (IOException | RuntimeException e) {
            LOG.warnv("SES content scan could not parse the message as MIME, only the wire bytes were checked: {0}",
                    e.getMessage());
            return false;
        }
    }

    // One needle per alignment: the signature is encoded behind 0, 1 or 2 leading bytes and only
    // the 4-character groups that lie fully inside it are kept, so each needle is 88 characters
    // that depend on nothing but the signature.
    static byte[][] base64Alignments(byte[] signature) {
        byte[][] needles = new byte[3][];
        for (int offset = 0; offset < 3; offset++) {
            byte[] padded = new byte[offset + signature.length];
            System.arraycopy(signature, 0, padded, offset, signature.length);
            String encoded = Base64.getEncoder().withoutPadding().encodeToString(padded);
            int start = offset == 0 ? 0 : 4;
            int end = (padded.length / 3) * 4;
            needles[offset] = encoded.substring(start, end).getBytes(StandardCharsets.US_ASCII);
        }
        return needles;
    }

    private static int indexOfIgnoringLineBreaks(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i < haystack.length; i++) {
            // A match never starts on a line break, and starting there would rescan the whole run.
            if (haystack[i] == '\r' || haystack[i] == '\n') {
                continue;
            }
            int h = i;
            int n = 0;
            while (n < needle.length && h < haystack.length) {
                byte b = haystack[h];
                if (b == '\r' || b == '\n') {
                    h++;
                    continue;
                }
                if (b != needle[n]) {
                    break;
                }
                n++;
                h++;
            }
            if (n == needle.length) {
                return i;
            }
        }
        return -1;
    }

    // A needle covers only the whole groups inside the signature, so the group before and the
    // group after the match are gathered too and the run is decoded to check every byte.
    private static boolean base64RunContainsSignature(byte[] haystack, int matchStart) {
        byte[] run = new byte[4 + BASE64_SIGNATURES[0].length + 4];
        int before = 0;
        for (int i = matchStart - 1; i >= 0 && before < 4; i--) {
            byte b = haystack[i];
            if (b == '\r' || b == '\n') {
                continue;
            }
            if (!isBase64(b)) {
                break;
            }
            run[3 - before] = b;
            before++;
        }
        int length = before;
        int start = 4 - before;
        for (int i = matchStart; i < haystack.length && length < run.length - start; i++) {
            byte b = haystack[i];
            if (b == '\r' || b == '\n') {
                continue;
            }
            if (!isBase64(b)) {
                break;
            }
            run[start + length] = b;
            length++;
        }
        if (length % 4 == 1) {
            length--;
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(Arrays.copyOfRange(run, start, start + length));
            return indexOf(decoded, SIGNATURE) >= 0;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean isBase64(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                || b == '+' || b == '/' || b == '=';
    }

    private static boolean entityContains(Entity entity) throws IOException {
        if (headerContains(entity.getHeader())) {
            return true;
        }
        Body body = entity.getBody();
        // A forwarded message (message/rfc822) is a Message body, neither multipart nor a leaf.
        if (body instanceof Message embedded) {
            return entityContains(embedded);
        }
        if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                if (entityContains(part)) {
                    return true;
                }
            }
            return false;
        }
        if (body instanceof SingleBody single) {
            try (InputStream in = single.getInputStream()) {
                return streamContains(in, SIGNATURE);
            }
        }
        return false;
    }

    // Header values are scanned decoded: an RFC 2047 encoded word splits the base64 spelling of
    // the signature across words, so the wire scan cannot see it.
    private static boolean headerContains(Header header) {
        if (header == null) {
            return false;
        }
        for (Field field : header.getFields()) {
            String decoded = DecoderUtil.decodeEncodedWords(field.getBody(), DecodeMonitor.SILENT);
            if (decoded != null && decoded.contains(SIGNATURE_TEXT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Searches the whole stream for {@code needle} while holding at most one chunk plus the
     * {@code needle.length - 1} bytes carried over from the previous chunk, so a match that
     * straddles a chunk boundary is still found.
     */
    static boolean streamContains(InputStream in, byte[] needle) throws IOException {
        int overlap = needle.length - 1;
        byte[] window = new byte[overlap + CHUNK_SIZE];
        int carried = 0;
        int read;
        while ((read = in.read(window, carried, CHUNK_SIZE)) > 0) {
            int filled = carried + read;
            if (indexOf(window, filled, needle) >= 0) {
                return true;
            }
            carried = Math.min(overlap, filled);
            System.arraycopy(window, filled - carried, window, 0, carried);
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, haystack.length, needle);
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        outer:
        for (int i = 0; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
