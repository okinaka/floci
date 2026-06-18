package io.floci.conformance;

import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.encode.RestXmlEncoder;
import io.floci.conformance.encode.AwsJsonEncoder;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.invoke.RestXmlInvoker;
import io.floci.conformance.invoke.AwsJsonInvoker;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.report.JsonReportWriter;
import io.floci.conformance.report.MarkdownReportWriter;
import io.floci.conformance.report.ReportMeta;
import io.floci.conformance.runner.ConformanceRunner;
import io.floci.conformance.runner.DependencySeeder;
import io.floci.conformance.util.AllGenerators;
import io.floci.conformance.util.HealthProbe;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Report-generation driver: runs the full conformance suite — every input
 * generator plus the two-step write/read scenarios — against the emulator at
 * {@code FLOCI_BASE_URL} (default {@code http://localhost:4566}) and writes
 * the human-readable Markdown report and machine-diffable JSON report to
 * {@code target/conformance-ses.{md,json}} (SES v1) and
 * {@code target/conformance-sesv2.{md,json}} (SES v2).
 *
 * <p>Skips when no emulator is reachable, so CI without one stays green.
 * To produce reports locally:
 * <pre>
 *   docker run -p 4566:4566 --rm floci/floci:latest &amp;
 *   mvn -q test -Dtest=ReportingRunTest
 *   ls target/conformance-*.md target/conformance-*.json
 * </pre>
 *
 * <p>The target doesn't have to be Floci — any LocalStack-compatible emulator
 * on the same port works, which is how cross-implementation comparisons are
 * produced.
 */
class ReportingRunTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("FLOCI_BASE_URL", "http://localhost:4566");
    private static final Path OUT_DIR = Path.of("target");


    @Test
    void sesV1_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSesV1();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new QueryInvoker(BASE_URL + "/", "2010-12-01", "ses"),
                new QueryFormEncoder(model),
                AllGenerators.ALL);

        List<VariantResult> results = new java.util.ArrayList<>(
                runner.run("com.amazonaws.ses#SimpleEmailService"));
        results.addAll(runner.runRoundTrip("com.amazonaws.ses#SimpleEmailService"));
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
                new RestJsonInvoker(BASE_URL, "ses"),
                new RestJsonEncoder(model),
                AllGenerators.ALL,
                DependencySeeder.sesV2());

        List<VariantResult> results = new java.util.ArrayList<>(
                runner.run("com.amazonaws.sesv2#SimpleEmailService_v2"));
        results.addAll(runner.runRoundTrip("com.amazonaws.sesv2#SimpleEmailService_v2"));
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.sesv2#SimpleEmailService_v2", "2019-09-27", Instant.now().toString());

        writeReports("conformance-sesv2", meta, results);
    }

    @Test
    void s3_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadS3();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new RestXmlInvoker(BASE_URL, "s3"),
                new RestXmlEncoder(model),
                AllGenerators.ALL);

        List<VariantResult> results = new java.util.ArrayList<>(
                runner.run("com.amazonaws.s3#AmazonS3"));
        results.addAll(runner.runRoundTrip("com.amazonaws.s3#AmazonS3"));
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.s3#AmazonS3", "2006-03-01", Instant.now().toString());

        writeReports("conformance-s3", meta, results);
    }

    @Test
    void ssm_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadSsm();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new AwsJsonInvoker(BASE_URL + "/", "AmazonSSM", "ssm", AwsJsonInvoker.Flavor.AWS_JSON_1_1),
                AwsJsonEncoder.json11(),
                AllGenerators.ALL);

        List<VariantResult> results = new java.util.ArrayList<>(
                runner.run("com.amazonaws.ssm#AmazonSSM"));
        results.addAll(runner.runRoundTrip("com.amazonaws.ssm#AmazonSSM"));
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.ssm#AmazonSSM", "2014-11-06", Instant.now().toString());

        writeReports("conformance-ssm", meta, results);
    }

    @Test
    void dynamodb_reports() throws Exception {
        assumeFloci();
        Model model = SmithyModelLoader.loadDynamoDb();
        ConformanceRunner runner = new ConformanceRunner(
                model,
                new AwsJsonInvoker(BASE_URL + "/", "DynamoDB_20120810", "dynamodb",
                        AwsJsonInvoker.Flavor.AWS_JSON_1_0),
                AwsJsonEncoder.json10(),
                AllGenerators.ALL);

        List<VariantResult> results = new java.util.ArrayList<>(
                runner.run("com.amazonaws.dynamodb#DynamoDB_20120810"));
        results.addAll(runner.runRoundTrip("com.amazonaws.dynamodb#DynamoDB_20120810"));
        ReportMeta meta = new ReportMeta(
                "com.amazonaws.dynamodb#DynamoDB_20120810", "2012-08-10", Instant.now().toString());

        writeReports("conformance-dynamodb", meta, results);
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
        HealthProbe.assumeReachable(BASE_URL);
    }
}
