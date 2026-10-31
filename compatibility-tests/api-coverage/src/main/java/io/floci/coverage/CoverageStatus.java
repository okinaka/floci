package io.floci.coverage;

/**
 * Whether one operation is dispatched by the emulator, judged from a single
 * stateless probe. Ordered from strongest to weakest evidence of an
 * implementation, which is the order {@link ServiceProbe} keeps the best of
 * when a service speaks several protocols.
 */
public enum CoverageStatus {
    /** 2xx, or a 4xx carrying an AWS error type: the request reached a handler. */
    IMPLEMENTED,
    /**
     * An S3-only error ({@code NoSuchBucket}, ...) from a service other than
     * S3: S3's catch-all {@code /{Bucket}/{Key+}} answered a path the service
     * itself does not route, so it is not counted as implemented.
     */
    FOREIGN_ERROR,
    /** A 4xx without an error type that is not a 404 / 406, or any 4xx to a HEAD request (never has a body). */
    AMBIGUOUS,
    /** A 5xx that is not a not-implemented signal: something answered, then failed. */
    SERVER_ERROR,
    /**
     * An explicit not-implemented signal (code, or a message naming the
     * operation as unsupported), or a 404 / 406 without an AWS error type.
     */
    NOT_IMPLEMENTED,
    /** The probe could not encode or send the request. */
    PROBE_FAILED
}
