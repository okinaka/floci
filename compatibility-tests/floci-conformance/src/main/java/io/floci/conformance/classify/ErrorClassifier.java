package io.floci.conformance.classify;

import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refines the meaning of a 4xx response by looking at the {@code __type} /
 * {@code <Code>} string Floci returned.
 *
 * <p>Three buckets of evidence, checked in priority order:
 * <ol>
 *   <li>"Floci doesn't implement this op" — the error type is on a small
 *       allowlist of explicit not-implemented signals (e.g.
 *       {@code UnsupportedOperation}).
 *   <li>"Op exists but harness collided with state" — the error type matches
 *       a state-collision pattern ({@code *AlreadyExists}, {@code *InUse}, …).
 *   <li>"Op exists and validated harness input" — the error type matches a
 *       validation pattern ({@code Invalid*}, {@code Validation*}, …) <em>or</em>
 *       it's declared in the operation's Smithy {@code errors} list.
 * </ol>
 *
 * <p>Anything else returns {@link Category#OTHER} so the runner can treat it
 * as a real wrong-error-type drift.
 */
public final class ErrorClassifier {

    public enum Category {
        NOT_IMPLEMENTED,
        /**
         * State collision: the resource already exists / is in use / quota
         * exceeded / etc. Prior tests typically caused this.
         */
        STATE,
        /**
         * Resource not found / does not exist. The synthetic identifier didn't
         * match any seeded data — distinct from STATE because the remedy is
         * "seed before reading", not "reset state".
         */
        MISSING,
        VALIDATION,
        DECLARED_BY_OP,
        OTHER
    }

    private static final Set<String> NOT_IMPLEMENTED_TYPES = Set.of(
            "UnsupportedOperation",
            "UnsupportedOperationException",
            "NotImplemented",
            "NotImplementedException",
            "OperationNotPermitted",
            "OperationNotPermittedException",
            // "InvalidAction" is the awsQuery-specific code for "the service
            // does not know this Action" — emitted by ministack at HTTP 400 and
            // by fakecloud at HTTP 501. Either way, the op isn't dispatched.
            "InvalidAction"
    );

    private static final List<Pattern> STATE_PATTERNS = List.of(
            Pattern.compile(".*AlreadyExists(Exception)?$"),
            Pattern.compile(".*InUse(Exception)?$"),
            Pattern.compile(".*LimitExceeded(Exception)?$"),
            Pattern.compile(".*Conflict(Exception)?$"),
            Pattern.compile("ResourceInUse.*"),
            Pattern.compile(".*AccountSuspended.*"),
            Pattern.compile(".*Throttling(Exception)?$"),
            Pattern.compile(".*TooManyRequests(Exception)?$")
    );

    private static final List<Pattern> MISSING_PATTERNS = List.of(
            Pattern.compile(".*ResourceNotFound.*"),
            Pattern.compile(".*NotFound(Exception)?$"),
            Pattern.compile(".*DoesNotExist(Exception)?$"),
            Pattern.compile(".*NoSuch.*")
    );

    private static final List<Pattern> VALIDATION_PATTERNS = List.of(
            Pattern.compile("Invalid.*"),
            Pattern.compile(".*Validation.*"),
            Pattern.compile(".*Malformed.*"),
            Pattern.compile(".*MissingParameter.*"),
            Pattern.compile(".*MissingRequiredParameter.*"),
            Pattern.compile(".*ParameterValue.*"),
            Pattern.compile(".*BadRequest.*"),
            Pattern.compile(".*IncompleteSignature.*"),
            Pattern.compile(".*RequestExpired.*")
    );

    /**
     * Classify an error response by HTTP status and extracted error type.
     * {@code rawType} may be {@code null}.
     *
     * <p>Status carries "not implemented" semantics on its own for some
     * emulators: HTTP 501 (fakecloud's InvalidAction, LocalStack's
     * InternalFailure) and HTTP 405 (ministack's MethodNotAllowed) both mean
     * the operation isn't dispatched, regardless of the error-type name.
     */
    public Category classify(OperationShape op, int httpStatus, String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return Category.OTHER;
        }
        if (httpStatus == 501 || httpStatus == 405) {
            return Category.NOT_IMPLEMENTED;
        }
        String name = normalize(rawType);

        if (NOT_IMPLEMENTED_TYPES.contains(name)) {
            return Category.NOT_IMPLEMENTED;
        }
        if (matchesAny(name, MISSING_PATTERNS)) {
            return Category.MISSING;
        }
        if (matchesAny(name, STATE_PATTERNS)) {
            return Category.STATE;
        }
        if (matchesAny(name, VALIDATION_PATTERNS)) {
            return Category.VALIDATION;
        }
        if (isDeclaredByOp(op, name)) {
            return Category.DECLARED_BY_OP;
        }
        return Category.OTHER;
    }

    /** Whether {@code name} matches any error shape declared on the op. */
    public boolean isDeclaredByOp(OperationShape op, String rawType) {
        if (rawType == null) {
            return false;
        }
        String name = normalize(rawType);
        for (ShapeId errId : op.getErrors()) {
            if (errId.getName().equals(name)) {
                return true;
            }
        }
        return false;
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

    private static boolean matchesAny(String name, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }
}
