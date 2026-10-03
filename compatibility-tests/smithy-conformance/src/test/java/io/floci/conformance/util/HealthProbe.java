package io.floci.conformance.util;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Decides whether a conformance test should run by checking that <em>some</em>
 * emulator is listening at {@code baseUrl}. Emulator-agnostic on purpose —
 * Floci, fakecloud, LocalStack, ministack and friends all expose different
 * health endpoints, but they all answer SOMETHING at the root.
 *
 * <p>Probing strategy, in order:
 * <ol>
 *   <li>If {@code CONFORMANCE_HEALTH_PATH} (env) is set, probe that path only —
 *       useful for emulators that 5xx on {@code /} but expose a known endpoint.
 *   <li>Otherwise probe a short list of well-known emulator health paths plus
 *       the root. The first response with status {@code &lt; 500} counts as
 *       reachable.
 * </ol>
 *
 * <p>"Reachable" here means "the harness can talk HTTP to the target" — not
 * "the target is healthy". An emulator returning 401 / 404 / 405 still counts;
 * the per-test variants will surface protocol-level issues themselves.
 *
 * <p>If nothing answers at any of the candidate paths, the calling test is
 * skipped via JUnit {@code Assumptions.abort(...)}.
 */
public final class HealthProbe {

    private static final List<String> DEFAULT_PATHS = List.of(
            "/_floci/health",
            "/_localstack/health",
            "/_fakecloud/health",
            "/_ministack/health",
            "/");

    private HealthProbe() {
    }

    public static void assumeReachable(String baseUrl) {
        List<String> paths = candidatePaths();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        String lastError = null;
        for (String path : paths) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() < 500) {
                    return;
                }
                lastError = path + " returned " + resp.statusCode();
            } catch (IOException e) {
                lastError = path + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = path + ": interrupted";
                break;
            }
        }
        Assumptions.abort("No reachable emulator at " + baseUrl
                + " (last attempt: " + lastError + ")");
    }

    private static List<String> candidatePaths() {
        String override = System.getenv("CONFORMANCE_HEALTH_PATH");
        if (override != null && !override.isBlank()) {
            return List.of(override);
        }
        return DEFAULT_PATHS;
    }
}
