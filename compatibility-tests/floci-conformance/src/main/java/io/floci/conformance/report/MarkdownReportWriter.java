package io.floci.conformance.report;

import io.floci.conformance.model.VariantResult;
import io.floci.conformance.model.Verdict;

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders a human-readable Markdown report: summary, per-operation rollup,
 * and per-failure detail.
 */
public final class MarkdownReportWriter implements ReportWriter {

    @Override
    public void write(ReportMeta meta, List<VariantResult> results, Writer out) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append("# Floci Conformance Report\n\n");
        sb.append("- **Service**: `").append(meta.serviceShapeId()).append("`\n");
        sb.append("- **Model version**: `").append(meta.modelVersion()).append("`\n");
        sb.append("- **Generated at**: ").append(meta.generatedAt()).append("\n\n");

        writeSummary(sb, results);
        writeOperationTable(sb, results);
        writeFailures(sb, results);

        out.write(sb.toString());
    }

    private void writeSummary(StringBuilder sb, List<VariantResult> results) {
        Map<Verdict, Long> byVerdict = countByVerdict(results);
        long total = results.size();
        long passed = byVerdict.getOrDefault(Verdict.PASS, 0L);

        sb.append("## Summary\n\n");
        sb.append("- **Total cases**: ").append(total).append('\n');
        sb.append("- **Passed**: ").append(passed)
                .append(" (").append(formatPercent(passed, total)).append(")\n\n");
        sb.append("| Verdict | Count |\n");
        sb.append("|---------|------:|\n");
        for (Verdict v : Verdict.values()) {
            long count = byVerdict.getOrDefault(v, 0L);
            if (count == 0) {
                continue;
            }
            sb.append("| `").append(v.name()).append("` | ").append(count).append(" |\n");
        }
        sb.append('\n');
    }

    private void writeOperationTable(StringBuilder sb, List<VariantResult> results) {
        Map<String, List<VariantResult>> byOp = new TreeMap<>(
                results.stream().collect(Collectors.groupingBy(r -> r.variant().operationName())));

        sb.append("## Per-operation rollup\n\n");
        sb.append("| Operation | Cases | Passed | Top failure verdict |\n");
        sb.append("|-----------|------:|-------:|---------------------|\n");
        for (var entry : byOp.entrySet()) {
            List<VariantResult> opResults = entry.getValue();
            long total = opResults.size();
            long passed = opResults.stream().filter(VariantResult::passed).count();
            String top = topFailureVerdict(opResults);
            sb.append("| `").append(entry.getKey()).append("` | ")
                    .append(total).append(" | ")
                    .append(passed).append(" | ")
                    .append(top).append(" |\n");
        }
        sb.append('\n');
    }

    private void writeFailures(StringBuilder sb, List<VariantResult> results) {
        List<VariantResult> failures = results.stream()
                .filter(r -> !r.passed())
                .sorted(Comparator
                        .comparing((VariantResult r) -> r.variant().operationName())
                        .thenComparing(r -> r.variant().generator()))
                .toList();

        sb.append("## Failures\n\n");
        if (failures.isEmpty()) {
            sb.append("_None._\n");
            return;
        }
        for (VariantResult r : failures) {
            sb.append("### `").append(r.variant().operationName()).append("` — ")
                    .append(r.variant().generator()).append('\n');
            sb.append("- **Verdict**: `").append(r.verdict().name()).append("`\n");
            sb.append("- **HTTP status**: ").append(r.httpStatus()).append('\n');
            if (r.errorType() != null) {
                sb.append("- **Error type**: `").append(r.errorType()).append("`\n");
            }
            if (r.detail() != null && !r.detail().isBlank()) {
                sb.append("- **Detail**: ").append(singleLine(r.detail())).append('\n');
            }
            sb.append('\n');
        }
    }

    private static Map<Verdict, Long> countByVerdict(List<VariantResult> results) {
        Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
        for (VariantResult r : results) {
            counts.merge(r.verdict(), 1L, Long::sum);
        }
        return counts;
    }

    private static String topFailureVerdict(List<VariantResult> opResults) {
        return opResults.stream()
                .filter(r -> !r.passed())
                .collect(Collectors.groupingBy(VariantResult::verdict, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> "`" + e.getKey().name() + "` (" + e.getValue() + ")")
                .orElse("—");
    }

    private static String formatPercent(long part, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", 100.0 * part / total);
    }

    private static String singleLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
