package io.floci.coverage.encode;

import io.floci.coverage.model.ProbeCase;
import io.floci.coverage.model.Variant;

/**
 * Converts a {@link ProbeCase}'s protocol-agnostic logical input into a
 * wire-ready {@link Variant} for one specific AWS protocol.
 *
 * <p>One implementation per protocol. A {@link ProbeCase} is protocol-agnostic;
 * the probe picks the encoder that matches the invoker it will send with.
 */
public interface RequestEncoder {

    /** Smithy protocol ID, e.g. {@code aws.protocols#awsQuery}. */
    String protocol();

    Variant encode(ProbeCase probeCase);
}
