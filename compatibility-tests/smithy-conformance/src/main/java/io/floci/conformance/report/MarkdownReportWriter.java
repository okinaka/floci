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
        writeInconclusive(sb, results);

        out.write(sb.toString());
    }

    /** True for verdicts that point to a real Floci bug, not "test couldn't fire". */
    private static boolean isRealFailure(Verdict v) {
        return switch (v) {
            case FAIL_SHAPE, FAIL_ECHO, FAIL_SILENT_PASS, FAIL_4XX_UNROUTED,
                    FAIL_WRONG_ERROR_TYPE, FAIL_5XX, FAIL_DELETED_STILL_READABLE,
                    FAIL_CREATED_NOT_LISTED, HARNESS_ERROR -> true;
            default -> false;
        };
    }

    private static boolean isInconclusive(Verdict v) {
        return switch (v) {
            case NOT_IMPLEMENTED, INCONCLUSIVE_VALIDATION, INCONCLUSIVE_STATE,
                    INCONCLUSIVE_MISSING -> true;
            default -> false;
        };
    }

    private void writeSummary(StringBuilder sb, List<VariantResult> results) {
        Map<Verdict, Long> byVerdict = countByVerdict(results);
        long total = results.size();
        long passed = byVerdict.getOrDefault(Verdict.PASS, 0L);
        long failed = results.stream().filter(r -> isRealFailure(r.verdict())).count();
        long inconclusive = results.stream().filter(r -> isInconclusive(r.verdict())).count();

        sb.append("## Summary\n\n");
        sb.append("- **Total cases**: ").append(total).append('\n');
        sb.append("- **Passed**: ").append(passed)
                .append(" (").append(formatPercent(passed, total)).append(")\n");
        sb.append("- **Real failures**: ").append(failed)
                .append(" (").append(formatPercent(failed, total)).append(")\n");
        sb.append("- **Inconclusive**: ").append(inconclusive)
                .append(" (").append(formatPercent(inconclusive, total)).append(")\n\n");
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
        sb.append("| Operation | Cases | Passed | Failures | Top real failure |\n");
        sb.append("|-----------|------:|-------:|---------:|------------------|\n");
        for (var entry : byOp.entrySet()) {
            List<VariantResult> opResults = entry.getValue();
            long total = opResults.size();
            long passed = opResults.stream().filter(VariantResult::passed).count();
            long fail = opResults.stream().filter(r -> isRealFailure(r.verdict())).count();
            String top = topRealFailureVerdict(opResults);
            sb.append("| `").append(entry.getKey()).append("` | ")
                    .append(total).append(" | ")
                    .append(passed).append(" | ")
                    .append(fail).append(" | ")
                    .append(top).append(" |\n");
        }
        sb.append('\n');
    }

    private void writeFailures(StringBuilder sb, List<VariantResult> results) {
        List<VariantResult> failures = results.stream()
                .filter(r -> isRealFailure(r.verdict()))
                .sorted(Comparator
                        .comparing((VariantResult r) -> r.variant().operationName())
                        .thenComparing(r -> r.variant().generator()))
                .toList();

        sb.append("## Failures\n\n");
        sb.append("_Real failures — Floci returned a wrong shape, silent-passed a negative test, " +
                "or otherwise misbehaved._\n\n");
        if (failures.isEmpty()) {
            sb.append("_None._\n\n");
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

    private void writeInconclusive(StringBuilder sb, List<VariantResult> results) {
        var grouped = results.stream()
                .filter(r -> isInconclusive(r.verdict()))
                .collect(Collectors.groupingBy(
                        r -> r.verdict(),
                        () -> new java.util.EnumMap<>(Verdict.class),
                        Collectors.mapping(r -> r.variant().operationName() + " (" + r.variant().generator() + ")",
                                Collectors.toCollection(java.util.TreeSet::new))));

        sb.append("## Inconclusive\n\n");
        sb.append("_Tests that didn't fail Floci but couldn't reach a verdict — operations " +
                "not implemented, state collisions from prior cases, or harness input that " +
                "didn't satisfy the op's validation._\n\n");
        if (grouped.isEmpty()) {
            sb.append("_None._\n");
            return;
        }
        for (var entry : grouped.entrySet()) {
            sb.append("### `").append(entry.getKey().name()).append("` (")
                    .append(entry.getValue().size()).append(")\n");
            for (String case_ : entry.getValue()) {
                sb.append("- ").append(case_).append('\n');
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

    private static String topRealFailureVerdict(List<VariantResult> opResults) {
        return opResults.stream()
                .filter(r -> isRealFailure(r.verdict()))
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
