package io.floci.conformance;

import io.floci.conformance.baseline.Baseline;
import io.floci.conformance.baseline.BaselineDiff;
import io.floci.conformance.baseline.BaselineGate;
import io.floci.conformance.baseline.BaselineStore;
import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.encode.RestXmlEncoder;
import io.floci.conformance.encode.AwsJson11Encoder;
import io.floci.conformance.encode.RequestEncoder;
import io.floci.conformance.invoke.Invoker;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.invoke.RestXmlInvoker;
import io.floci.conformance.invoke.AwsJson11Invoker;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.report.ReportMeta;
import io.floci.conformance.runner.ConformanceRunner;
import io.floci.conformance.util.AllGenerators;
import io.floci.conformance.util.HealthProbe;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate: runs the full conformance suite against the emulator,
 * diffs the per-case verdicts against the committed snapshots under
 * {@code baselines/}, and fails the test when a previously passing (or
 * inconclusive) case turns into a real failure. A Markdown diff is always
 * written to {@code target/baseline-diff-*.md} for review.
 *
 * <p>Two modes, switched by the {@code conformance.capture} system property:
 * <ul>
 *   <li>{@code -Dconformance.capture=true} (or env {@code CONFORMANCE_CAPTURE=true}) —
 *       OVERWRITES the committed baseline JSON with the current run. Use this
 *       deliberately when you've improved or accepted new behavior; commit
 *       the diff in the same PR.
 *   <li>Default — compares the current run against the committed baseline and
 *       fails on regression. Diff is always written to
 *       {@code target/baseline-diff-*.md} for human review.
 * </ul>
 *
 * <p>Skips entirely when Floci is unreachable.
 */
class BaselineGateTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("FLOCI_BASE_URL", "http://localhost:4566");
    private static final Path BASELINE_DIR = Path.of("baselines");
    private static final Path TARGET_DIR = Path.of("target");


    @Test
    void gate_sesV1() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSesV1();
        runGate("ses-v1",
                "com.amazonaws.ses#SimpleEmailService", "2010-12-01",
                model,
                new QueryInvoker(BASE_URL + "/", "2010-12-01", "ses"),
                new QueryFormEncoder(model));
    }

    @Test
    void gate_sesV2() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSesV2();
        runGate("ses-v2",
                "com.amazonaws.sesv2#SimpleEmailService_v2", "2019-09-27",
                model,
                new RestJsonInvoker(BASE_URL, "ses"),
                new RestJsonEncoder(model));
    }

    @Test
    void gate_s3() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadS3();
        runGate("s3",
                "com.amazonaws.s3#AmazonS3", "2006-03-01",
                model,
                new RestXmlInvoker(BASE_URL, "s3"),
                new RestXmlEncoder(model));
    }

    @Test
    void gate_ssm() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSsm();
        runGate("ssm",
                "com.amazonaws.ssm#AmazonSSM", "2014-11-06",
                model,
                new AwsJson11Invoker(BASE_URL + "/", "AmazonSSM", "ssm"),
                new AwsJson11Encoder());
    }

    private static void runGate(String stem, String serviceShapeId, String modelVersion,
                                Model model, Invoker invoker, RequestEncoder encoder) throws IOException {
        ConformanceRunner runner = new ConformanceRunner(model, invoker, encoder, AllGenerators.ALL);
        List<VariantResult> results = new java.util.ArrayList<>(runner.run(serviceShapeId));
        results.addAll(runner.runRoundTrip(serviceShapeId));
        ReportMeta meta = new ReportMeta(serviceShapeId, modelVersion, Instant.now().toString());
        Baseline current = Baseline.from(meta, results);

        Path baselineFile = BASELINE_DIR.resolve(stem + ".json");
        if (isCaptureMode()) {
            BaselineStore.writeFile(baselineFile, current);
            System.out.println("Captured baseline at " + baselineFile.toAbsolutePath());
            return;
        }

        Assumptions.assumeTrue(Files.exists(baselineFile),
                "Baseline " + baselineFile + " does not exist; run with -Dconformance.capture=true once.");

        Baseline baseline = BaselineStore.readFile(baselineFile);
        BaselineDiff diff = BaselineDiff.compute(baseline, current);
        BaselineGate.Result gate = BaselineGate.check(diff);

        Files.createDirectories(TARGET_DIR);
        Path diffMd = TARGET_DIR.resolve("baseline-diff-" + stem + ".md");
        try (Writer w = Files.newBufferedWriter(diffMd, StandardCharsets.UTF_8)) {
            w.write(diff.toMarkdown());
        }
        System.out.println("Wrote " + diffMd.toAbsolutePath() + " — " + gate.summary());

        if (!gate.passed()) {
            assertThat(gate.passed())
                    .as("baseline regression: %s\nsee %s", gate.summary(), diffMd.toAbsolutePath())
                    .isTrue();
        }
    }

    private static boolean isCaptureMode() {
        String prop = System.getProperty("conformance.capture", "");
        String env = System.getenv().getOrDefault("CONFORMANCE_CAPTURE", "");
        return "true".equalsIgnoreCase(prop) || "true".equalsIgnoreCase(env);
    }

    private static void assumeFloci() {
        HealthProbe.assumeReachable(BASE_URL);
    }
}
