package io.floci.conformance.model;

/**
 * The outcome of sending one {@link Variant} against the emulator.
 *
 * @param variant      The original variant the runner sent.
 * @param verdict      Pass / fail classification from {@link Verdict}.
 * @param httpStatus   HTTP status returned by the emulator (-1 if the variant
 *                     never reached the wire).
 * @param errorType    For 4xx responses, the {@code __type} / {@code <Code>}
 *                     value extracted from the body. {@code null} if absent.
 * @param detail       Free-text explanation. Drift findings, harness
 *                     exceptions, "missing field X" messages, etc.
 */
public record VariantResult(
        Variant variant,
        Verdict verdict,
        int httpStatus,
        String errorType,
        String detail) {

    public boolean passed() {
        return verdict == Verdict.PASS;
    }
}
