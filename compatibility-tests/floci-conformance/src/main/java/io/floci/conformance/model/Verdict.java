package io.floci.conformance.model;

/**
 * Outcome of running a single {@link Variant} against the emulator.
 *
 * <p>Distinct from any specific protocol or generator. The pass / fail boundary
 * for a given verdict depends on which generator produced the variant — e.g.
 * a {@code Negative}-strategy variant <i>expecting</i> a 4xx is {@link #PASS}
 * if it gets one, but a {@code Boundary}-strategy variant expecting a 200 is
 * {@link #FAIL_4XX_UNROUTED} if Floci returns a generic 404.
 */
public enum Verdict {
    /** Variant succeeded according to its generator's expectation. */
    PASS,
    /** Floci responded 200 but the body fails {@code @required} / type / enum checks. */
    FAIL_SHAPE,
    /** Floci returned 200 when the variant expected a 4xx (silent-pass bug). */
    FAIL_SILENT_PASS,
    /** Floci returned 4xx but with no AWS-shaped error body — routing or wire bug. */
    FAIL_4XX_UNROUTED,
    /** Floci returned 4xx with a {@code __type} the operation's Smithy {@code error_shapes} doesn't declare. */
    FAIL_WRONG_ERROR_TYPE,
    /** Floci returned 5xx (almost always a Floci-side bug). */
    FAIL_5XX,
    /** Variant could not be exercised at all (network, build, classifier failure). */
    HARNESS_ERROR
}
