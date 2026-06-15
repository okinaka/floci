package io.floci.conformance.invoke;

/**
 * Raw HTTP response from the emulator, before protocol-aware decoding.
 *
 * @param httpStatus  HTTP status code.
 * @param contentType Value of the {@code Content-Type} header (or {@code null}).
 * @param body        Response body as a string. May be empty but not null.
 */
public record InvocationResponse(int httpStatus, String contentType, String body) {

    public boolean is2xx() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public boolean is4xx() {
        return httpStatus >= 400 && httpStatus < 500;
    }

    public boolean is5xx() {
        return httpStatus >= 500 && httpStatus < 600;
    }
}
