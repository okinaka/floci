package io.floci.conformance.model;

/**
 * Outcome of running a single {@link Variant} against the emulator.
 *
 * <p>Three tiers:
 * <ul>
 *   <li>{@link #PASS} — the variant met its generator's expectation.
 *   <li>{@code INCONCLUSIVE_*} / {@link #NOT_IMPLEMENTED} — the variant didn't
 *       reach a useful verdict (Floci hasn't implemented the op, the harness
 *       sent invalid input, or state leftover from a prior case got in the
 *       way). Not a Floci bug, not a real test pass — just inconclusive.
 *   <li>{@code FAIL_*} — Floci behaved demonstrably wrong (silent-pass on a
 *       negative test, returned an undeclared error, returned 5xx, etc.).
 *   <li>{@link #HARNESS_ERROR} — harness-side problem (network, encoder bug);
 *       not Floci's fault.
 * </ul>
 */
public enum Verdict {
    /** Variant succeeded according to its generator's expectation. */
    PASS,

    /** Floci explicitly signalled "operation not implemented" (e.g. {@code UnsupportedOperation}). */
    NOT_IMPLEMENTED,
    /** Floci returned a valid validation error — harness input wasn't acceptable. */
    INCONCLUSIVE_VALIDATION,
    /**
     * Floci returned a state-collision error (e.g. {@code *AlreadyExists},
     * {@code *InUse}, {@code *LimitExceeded}). State from a prior test
     * pollutes the measurement.
     */
    INCONCLUSIVE_STATE,
    /**
     * Floci returned a not-found error (e.g. {@code *NotFound},
     * {@code *DoesNotExist}). The synthetic identifier the harness used
     * references something that doesn't exist — needs seeding via a Create
     * scenario before the Get/Describe/Delete can succeed.
     */
    INCONCLUSIVE_MISSING,

    /** Floci responded 200 but the body fails {@code @required} / type / enum checks. */
    FAIL_SHAPE,
    /**
     * Round-trip echo failed: a setup op accepted input X, the verify op
     * returned 200, but X did not round-trip to the response — Floci lost or
     * mutated the field somewhere in storage.
     */
    FAIL_ECHO,
    /** Floci returned 200 when the variant expected a 4xx (silent-pass bug). */
    FAIL_SILENT_PASS,
    /** Floci returned 4xx but with no AWS-shaped error body — routing or wire bug. */
    FAIL_4XX_UNROUTED,
    /** Floci returned 4xx with a {@code __type} the operation's Smithy {@code errors} doesn't declare. */
    FAIL_WRONG_ERROR_TYPE,
    /** Floci returned 5xx (almost always a Floci-side bug). */
    FAIL_5XX,

    /** Variant could not be exercised at all (network, build, classifier failure). */
    HARNESS_ERROR
}
