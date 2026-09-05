package io.floci.conformance.encode;

import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;

/**
 * Converts a {@link GeneratedCase}'s protocol-agnostic logical input into a
 * wire-ready {@link Variant} for one specific AWS protocol.
 *
 * <p>One implementation per protocol the runner supports. Generators stay
 * protocol-agnostic; the runner picks the encoder that matches its invoker.
 */
public interface RequestEncoder {

    /** Smithy protocol ID, e.g. {@code aws.protocols#awsQuery}. */
    String protocol();

    Variant encode(GeneratedCase generated);
}
