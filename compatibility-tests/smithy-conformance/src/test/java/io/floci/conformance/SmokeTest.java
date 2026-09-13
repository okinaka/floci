package io.floci.conformance;

import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.generator.EmptyInputGenerator;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.runner.ConformanceRunner;
import io.floci.conformance.util.HealthProbe;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal connectivity smoke: sends one safe operation per protocol
 * ({@code ListIdentities} over Query/XML, {@code ListEmailIdentities} over
 * REST JSON) through the full generator → encoder → invoker → classifier
 * pipeline and asserts the runner produces a verdict without crashing.
 *
 * <p>Use this to verify wiring after harness changes before paying for a full
 * {@link ReportingRunTest} run. Skips when no emulator is reachable on
 * {@code FLOCI_BASE_URL} (default {@code http://localhost:4566}).
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
                new QueryInvoker(BASE_URL + "/", "2010-12-01", "ses"),
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
                new RestJsonInvoker(BASE_URL, "ses"),
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
        HealthProbe.assumeReachable(BASE_URL);
    }
}
