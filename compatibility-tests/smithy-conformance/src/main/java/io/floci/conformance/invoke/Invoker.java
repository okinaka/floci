package io.floci.conformance.invoke;

import io.floci.conformance.model.Variant;

import java.io.IOException;

/**
 * Protocol-specific transport. One implementation per AWS wire protocol;
 * the runner picks the right one based on the operation's Smithy
 * {@code @protocol} trait.
 */
public interface Invoker {

    /**
     * @return the Smithy protocol shape ID this invoker handles
     *         (e.g. {@code aws.protocols#awsQuery}, {@code aws.protocols#restJson1}).
     */
    String protocol();

    /**
     * Send the variant against the emulator and return the raw response.
     *
     * @throws IOException on network failure. Application-level 4xx / 5xx are
     *                     reported via {@link InvocationResponse#httpStatus()}, not exceptions.
     */
    InvocationResponse send(Variant variant) throws IOException;
}
