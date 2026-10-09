package io.floci.coverage.classify;

import java.util.Set;

/**
 * Error types that mean "the emulator does not serve this operation", and the
 * normalization needed to recognise them on the wire.
 *
 * <p>Coverage asks one question of a response, so only the not-implemented
 * signals matter here: an {@code Invalid*} or {@code *NotFound} error proves
 * the request reached a handler just as well as a 2xx does, and the behaviour
 * suite's finer buckets (state collision, validation, declared-by-op) say
 * nothing about dispatch.
 */
public final class ErrorSignals {

    private ErrorSignals() {
    }

    private static final Set<String> NOT_IMPLEMENTED_TYPES = Set.of(
            "UnsupportedOperation",
            "UnsupportedOperationException",
            "NotImplemented",
            "NotImplementedException",
            "OperationNotPermitted",
            "OperationNotPermittedException",
            // DynamoDB's dialect for "this Action isn't supported here".
            "UnknownOperationException",
            "UnknownOperation",
            // "InvalidAction" is the awsQuery-specific code for "the service
            // does not know this Action": emitted by ministack at HTTP 400 and
            // by fakecloud at HTTP 501. Either way, the op isn't dispatched.
            "InvalidAction",
            // Floci's CloudFormation handler answers unknown actions with this.
            "UnknownAction"
    );

    /** Whether {@code rawType} is an explicit "operation not served" signal. */
    public static boolean isNotImplemented(String rawType) {
        return rawType != null && !rawType.isBlank() && NOT_IMPLEMENTED_TYPES.contains(normalize(rawType));
    }

    /**
     * Strip namespace and {@code Sender.} / {@code Receiver.} prefixes AWS
     * Query-protocol services tack onto error codes.
     */
    public static String normalize(String raw) {
        String n = raw;
        int hash = n.lastIndexOf('#');
        if (hash >= 0) {
            n = n.substring(hash + 1);
        }
        int dot = n.lastIndexOf('.');
        if (dot >= 0) {
            n = n.substring(dot + 1);
        }
        return n;
    }
}
