package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;

/**
 * Makes synthesized resource names unique per run and per case so the suite is
 * deterministic at the verdict level against a persistent emulator.
 *
 * <p>Every synthetic value shares the {@code cov-probe} token (see
 * {@link FormatHints}). Rewriting that token to {@code cov-probe-<salt>} keeps
 * the value format-valid (e.g. {@code cov-probe-<salt>@example.com}) while
 * giving each case its own namespace.
 *
 * <p>The salt has two parts:
 * <ul>
 *   <li>a <b>per-run nonce</b>, refreshed by {@link #startRun()} — so re-running
 *       against an emulator that kept the previous run's resources never
 *       collides, turning the previous {@code INCONCLUSIVE_STATE} /
 *       {@code FAIL_SILENT_PASS} noise into stable verdicts;</li>
 *   <li>a <b>per-case component</b> derived from the case label — so two cases
 *       of the same create operation (e.g. {@code optionals.required-only} and
 *       {@code optionals.all-members}) get different names and don't collide
 *       with each other within a run.</li>
 * </ul>
 *
 * <p>Keying the per-case component on the case label (not the operation) keeps
 * the runner's cross-operation read-after-write intact: a {@code CreateX} and a
 * {@code GetX} probe produced by the same generator share the label, hence the
 * same name, so the read still finds what the write seeded. The two-step
 * scenarios rely on the same property — their setup and verify steps carry one
 * scenario label.
 *
 * <p>State is process-global and the suite runs sequentially, so a single
 * current nonce is sufficient; {@link #startRun()} must be called at the start
 * of each run before any case is generated or sent.
 */
public final class NameSalt {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String TOKEN = "cov-probe";

    private static volatile String nonce = "";

    private NameSalt() {
    }

    /** Begin a fresh run: pick a new nonce so names never clash with prior runs. */
    public static void startRun() {
        nonce = Integer.toHexString(RANDOM.nextInt() & 0x00ffffff);
    }

    /** Current run nonce; empty before the first {@link #startRun()}. */
    public static String nonce() {
        return nonce;
    }

    /**
     * Stable RNG seed for a base string, mixed with the run nonce so seeded
     * fuzzers (which would otherwise reproduce identical values every run)
     * draw fresh values per run.
     */
    public static long seedFor(String base) {
        return (base + '#' + nonce).hashCode();
    }

    /**
     * Return a copy of {@code input} with every {@code cov-probe} token rewritten
     * to {@code cov-probe-<nonce><caseHash>}, unique per run and per case label.
     * Returns the input unchanged when there is nothing to rewrite.
     */
    public static JsonNode apply(JsonNode input, String caseLabel) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return input;
        }
        String replacement = TOKEN + '-' + nonce + caseHash(caseLabel);
        return rewrite(input, replacement);
    }

    private static String caseHash(String caseLabel) {
        return Integer.toHexString((caseLabel == null ? 0 : caseLabel.hashCode()) & 0x000fffff);
    }

    private static JsonNode rewrite(JsonNode node, String replacement) {
        if (node.isTextual()) {
            String text = node.textValue();
            return text.contains(TOKEN)
                    ? new TextNode(text.replace(TOKEN, replacement))
                    : node;
        }
        if (node.isObject()) {
            ObjectNode copy = NODES.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                copy.set(e.getKey(), rewrite(e.getValue(), replacement));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            for (JsonNode child : node) {
                copy.add(rewrite(child, replacement));
            }
            return copy;
        }
        return node;
    }
}
