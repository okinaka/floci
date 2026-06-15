package io.floci.conformance.model;

/**
 * What a {@link Variant} predicts about Floci's response. Generators tag every
 * variant they produce with one of these so the runner can compare verdicts
 * uniformly across positive and negative tests.
 */
public enum ExpectedOutcome {
    /** 200 OK with a body that conforms to the operation's output shape. */
    SUCCESS,
    /** 4xx with an AWS-shaped error body whose {@code __type} is one of the operation's declared errors. */
    CLIENT_ERROR
}
