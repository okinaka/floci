package io.floci.conformance;

import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.generator.EmptyInputGenerator;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.runner.ConformanceRunner;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke for Phase A: empty-input generator round-trips through
 * QueryInvoker (SES v1) and RestJsonInvoker (SES v2) against a running Floci.
 *
 * <p>The tests {@code Assumptions.assumeTrue(...)} away if Floci isn't reachable
 * on {@code FLOCI_BASE_URL} (default {@code http://localhost:4566}), so this
 * file is safe in CI without spinning up the emulator.
 */
class SmokeTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("FLOCI_BASE_URL", "http://localhost:4566");

    @Test
    void sesV1_emptyInput_listIdentities_routes() {
        assumeFloci();

        Model model = SmithyModelLoader.loadSesV1();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new QueryInvoker(BASE_URL + "/", "2010-12-01"),
                new QueryFormEncoder(model),
                List.of(new EmptyInputGenerator()));

        List<VariantResult> results = runner.runOperations(
                "com.amazonaws.ses#SimpleEmailService",
                Set.of("ListIdentities"));

        assertThat(results).hasSize(1);
        VariantResult r = results.get(0);
        // Whatever the verdict, the runner must never explode on a known-good op.
        assertThat(r.verdict()).isNotNull();
        assertThat(r.httpStatus()).isGreaterThanOrEqualTo(200);
    }

    @Test
    void sesV2_emptyInput_listEmailIdentities_routes() {
        assumeFloci();

        Model model = SmithyModelLoader.loadSesV2();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new RestJsonInvoker(BASE_URL),
                new RestJsonEncoder(model),
                List.of(new EmptyInputGenerator()));

        List<VariantResult> results = runner.runOperations(
                "com.amazonaws.sesv2#SimpleEmailService_v2",
                Set.of("ListEmailIdentities"));

        assertThat(results).hasSize(1);
        VariantResult r = results.get(0);
        assertThat(r.verdict()).isNotNull();
        assertThat(r.httpStatus()).isGreaterThanOrEqualTo(200);
    }

    private static void assumeFloci() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/_floci/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            Assumptions.assumeTrue(resp.statusCode() < 500, "Floci not healthy at " + BASE_URL);
        } catch (IOException | InterruptedException e) {
            Assumptions.abort("Floci not reachable at " + BASE_URL + ": " + e.getMessage());
        }
    }
}
