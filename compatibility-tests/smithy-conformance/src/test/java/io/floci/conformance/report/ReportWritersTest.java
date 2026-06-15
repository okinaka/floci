package io.floci.conformance.report;

import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.model.Verdict;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link MarkdownReportWriter} and
 * {@link JsonReportWriter} using hand-built results: section layout
 * (summary / per-op rollup / failures / inconclusive), verdict
 * categorization, sorted output, and byte-for-byte JSON determinism.
 */
class ReportWritersTest {

    private static final ReportMeta META = new ReportMeta(
            "com.example#FakeService", "2024-01-01", "2026-06-09T00:00:00Z");

    private static OperationShape op(String name) {
        return OperationShape.builder()
                .id(ShapeId.fromParts("com.example", name))
                .build();
    }

    private static Variant variant(String op, String generator) {
        return new Variant(op(op), generator, Map.of(), Map.of(), Map.of(),
                null, ExpectedOutcome.SUCCESS, null);
    }

    private static final List<VariantResult> RESULTS = List.of(
            new VariantResult(variant("ListThings", "empty.passthrough"),
                    Verdict.PASS, 200, null, null),
            new VariantResult(variant("ListThings", "optionals.all-members"),
                    Verdict.PASS, 200, null, null),
            new VariantResult(variant("CreateThing", "empty.passthrough"),
                    Verdict.FAIL_4XX_UNROUTED, 404, null, "no route"),
            new VariantResult(variant("CreateThing", "negative.missing-required.Name"),
                    Verdict.FAIL_SILENT_PASS, 200, null, "expected error but got 2xx")
    );

    @Test
    void markdown_includes_meta_summary_and_failures() throws Exception {
        StringWriter w = new StringWriter();
        new MarkdownReportWriter().write(META, RESULTS, w);
        String md = w.toString();

        assertThat(md).contains("# Floci Conformance Report");
        assertThat(md).contains("com.example#FakeService");
        assertThat(md).contains("2024-01-01");
        assertThat(md).contains("**Total cases**: 4");
        // 2/4 = 50.0%
        assertThat(md).contains("50.0%");
        // Failures section present
        assertThat(md).contains("FAIL_4XX_UNROUTED");
        assertThat(md).contains("FAIL_SILENT_PASS");
        // Per-op table mentions both operations
        assertThat(md).contains("CreateThing");
        assertThat(md).contains("ListThings");
    }

    @Test
    void markdown_no_failures_shows_none() throws Exception {
        List<VariantResult> allPass = List.of(
                new VariantResult(variant("Op", "g1"), Verdict.PASS, 200, null, null));
        StringWriter w = new StringWriter();
        new MarkdownReportWriter().write(META, allPass, w);
        assertThat(w.toString()).contains("_None._");
    }

    @Test
    void json_has_expected_schema_and_sorted_operations() throws Exception {
        StringWriter w = new StringWriter();
        new JsonReportWriter().write(META, RESULTS, w);
        String json = w.toString();

        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(root.get("meta").get("serviceShapeId").asText())
                .isEqualTo("com.example#FakeService");
        assertThat(root.get("summary").get("totalCases").asInt()).isEqualTo(4);
        assertThat(root.get("summary").get("byVerdict").get("PASS").asInt()).isEqualTo(2);
        // Sorted operations: CreateThing before ListThings
        assertThat(root.get("operations").get(0).get("name").asText()).isEqualTo("CreateThing");
        assertThat(root.get("operations").get(1).get("name").asText()).isEqualTo("ListThings");
    }

    @Test
    void json_is_deterministic() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        new JsonReportWriter().write(META, RESULTS, w1);
        new JsonReportWriter().write(META, RESULTS, w2);
        assertThat(w1.toString()).isEqualTo(w2.toString());
    }

    @Test
    void inconclusive_section_separates_NOT_IMPLEMENTED_and_INCONCLUSIVE_verdicts() throws Exception {
        List<VariantResult> mixed = List.of(
                new VariantResult(variant("Op1", "empty.passthrough"),
                        Verdict.NOT_IMPLEMENTED, 400, "UnsupportedOperation",
                        "operation not implemented"),
                new VariantResult(variant("Op2", "optionals.all-members"),
                        Verdict.INCONCLUSIVE_STATE, 400, "ConfigurationSetAlreadyExists",
                        "state collision"),
                new VariantResult(variant("Op3", "empty.passthrough"),
                        Verdict.FAIL_SILENT_PASS, 200, null, "expected error but got 2xx"));

        StringWriter w = new StringWriter();
        new MarkdownReportWriter().write(META, mixed, w);
        String md = w.toString();

        assertThat(md).contains("## Inconclusive");
        assertThat(md).contains("`NOT_IMPLEMENTED`");
        assertThat(md).contains("`INCONCLUSIVE_STATE`");
        // Real failures section still lists the silent-pass
        int failuresHeader = md.indexOf("## Failures");
        int inconclusiveHeader = md.indexOf("## Inconclusive");
        assertThat(failuresHeader).isLessThan(inconclusiveHeader);
        // Inconclusive items shouldn't appear under Failures
        String failuresSection = md.substring(failuresHeader, inconclusiveHeader);
        assertThat(failuresSection).doesNotContain("UnsupportedOperation");
        assertThat(failuresSection).doesNotContain("ConfigurationSetAlreadyExists");
        assertThat(failuresSection).contains("FAIL_SILENT_PASS");
    }
}
