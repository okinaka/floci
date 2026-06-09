package io.floci.conformance;

import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.generator.BoundaryGenerator;
import io.floci.conformance.generator.EmptyInputGenerator;
import io.floci.conformance.generator.EnumExhaustGenerator;
import io.floci.conformance.generator.Generator;
import io.floci.conformance.generator.IdentifierFanoutGenerator;
import io.floci.conformance.generator.ModelExamplesGenerator;
import io.floci.conformance.generator.NegativeGenerator;
import io.floci.conformance.generator.OptionalsGenerator;
import io.floci.conformance.generator.PropertyBasedGenerator;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.report.JsonReportWriter;
import io.floci.conformance.report.MarkdownReportWriter;
import io.floci.conformance.report.ReportMeta;
import io.floci.conformance.runner.ConformanceRunner;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * End-to-end report generation: runs every Phase B generator against SES v1
 * and v2 and writes Markdown and JSON reports under {@code target/}.
 *
 * <p>Skips when Floci isn't reachable, so CI without an emulator stays green.
 * To produce real reports locally:
 * <pre>
 *   docker run -p 4566:4566 --rm hectorvent/floci:latest &amp;
 *   mvn -q test -Dtest=ReportingRunTest
 *   ls target/conformance-*.md target/conformance-*.json
 * </pre>
 */
class ReportingRunTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("FLOCI_BASE_URL", "http://localhost:4566");
    private static final Path OUT_DIR = Path.of("target");

    private static final List<Generator> ALL_GENERATORS = List.of(
            new EmptyInputGenerator(),
            new OptionalsGenerator(),
            new EnumExhaustGenerator(),
            new NegativeGenerator(),
            new BoundaryGenerator(),
            new PropertyBasedGenerator(),
            new ModelExamplesGenerator(),
            new IdentifierFanoutGenerator());

    @Test
    void sesV1_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSesV1();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new QueryInvoker(BASE_URL + "/", "2010-12-01"),
                new QueryFormEncoder(model),
                ALL_GENERATORS);

        List<VariantResult> results = runner.run("com.amazonaws.ses#SimpleEmailService");
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.ses#SimpleEmailService", "2010-12-01", Instant.now().toString());

        writeReports("conformance-ses", meta, results);
    }

    @Test
    void sesV2_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSesV2();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new RestJsonInvoker(BASE_URL),
                new RestJsonEncoder(model),
                ALL_GENERATORS);

        List<VariantResult> results = runner.run("com.amazonaws.sesv2#SimpleEmailService_v2");
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.sesv2#SimpleEmailService_v2", "2019-09-27", Instant.now().toString());

        writeReports("conformance-sesv2", meta, results);
    }

    private static void writeReports(String stem, ReportMeta meta, List<VariantResult> results)
            throws IOException {
        Files.createDirectories(OUT_DIR);
        Path md = OUT_DIR.resolve(stem + ".md");
        Path json = OUT_DIR.resolve(stem + ".json");
        try (Writer w = Files.newBufferedWriter(md, StandardCharsets.UTF_8)) {
            new MarkdownReportWriter().write(meta, results, w);
        }
        try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            new JsonReportWriter().write(meta, results, w);
        }
        System.out.println("Wrote " + md.toAbsolutePath());
        System.out.println("Wrote " + json.toAbsolutePath());
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
