package io.floci.coverage.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.coverage.invoke.InvocationResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Pulls the AWS error type out of an error response body: {@code <Code>} for
 * the XML protocols, {@code __type} (or {@code code}) for the JSON ones.
 */
public final class ErrorTypes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XmlMapper XML = new XmlMapper();

    private ErrorTypes() {
    }

    /** The raw error type, or {@code null} when the body carries none. */
    public static String extract(InvocationResponse resp) {
        if (resp.body() == null || resp.body().isEmpty()) {
            return null;
        }
        String ct = resp.contentType() == null ? "" : resp.contentType().toLowerCase();
        try {
            if (ct.contains("xml") || resp.body().startsWith("<")) {
                JsonNode root = XML.readTree(resp.body().getBytes(StandardCharsets.UTF_8));
                JsonNode code = root.findValue("Code");
                return code != null && code.isTextual() ? code.asText() : null;
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode t = root.get("__type");
            if (t == null) {
                t = root.get("code");
            }
            return t != null && t.isTextual() ? t.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The error message, or {@code null} when the body carries none. */
    public static String message(InvocationResponse resp) {
        if (resp.body() == null || resp.body().isEmpty()) {
            return null;
        }
        String ct = resp.contentType() == null ? "" : resp.contentType().toLowerCase();
        try {
            JsonNode root = ct.contains("xml") || resp.body().startsWith("<")
                    ? XML.readTree(resp.body().getBytes(StandardCharsets.UTF_8))
                    : JSON.readTree(resp.body());
            JsonNode m = root.findValue("Message");
            if (m == null) {
                m = root.findValue("message");
            }
            return m != null && m.isTextual() ? m.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
