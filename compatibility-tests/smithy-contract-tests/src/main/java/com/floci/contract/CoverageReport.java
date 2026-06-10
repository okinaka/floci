package com.floci.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates per-operation coverage results and emits a markdown summary.
 * Owned by a single coverage-test run (one service × one protocol).
 */
public final class CoverageReport {

    public enum Status {
        /** {@code 200 OK} and the response body matches the Smithy output shape. */
        IMPLEMENTED_OK,
        /** {@code 200 OK} but the response body has at least one shape drift. */
        IMPLEMENTED_DRIFT,
        /** 4xx with a validation error code — op is dispatched, input fixture is too thin. */
        IMPLEMENTED_VALIDATION,
        /** 4xx with a not-found / does-not-exist code — op is dispatched, needs prior state. */
        IMPLEMENTED_STATE,
        /** 4xx with {@code UnsupportedOperation} or equivalent — op is not dispatched. */
        NOT_IMPLEMENTED,
        /** 5xx or an unclassified failure. Treated as harness-bug-suspect, not coverage. */
        ERROR
    }

    public record Entry(String op, Status status, int httpStatus, String note,
                        FieldCoverage.Result fieldCoverage) {
        public Entry(String op, Status status, int httpStatus, String note) {
            this(op, status, httpStatus, note, null);
        }
        public boolean isImplemented() {
            return status != Status.NOT_IMPLEMENTED && status != Status.ERROR;
        }
    }

    private final String title;
    private final List<Entry> entries = new ArrayList<>();

    public CoverageReport(String title) {
        this.title = title;
    }

    public void record(Entry e) {
        entries.add(e);
    }

    public int total() {
        return entries.size();
    }

    public int countBy(Status s) {
        return (int) entries.stream().filter(e -> e.status == s).count();
    }

    public int implemented() {
        return (int) entries.stream().filter(Entry::isImplemented).count();
    }

    public String toMarkdown() {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, countBy(s));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        sb.append("## Summary\n\n");
        sb.append("Total operations: ").append(total()).append("\n");
        sb.append("Implemented (any non-`NOT_IMPLEMENTED`/`ERROR`): ").append(implemented())
                .append(" / ").append(total())
                .append(" (").append(percent(implemented(), total())).append("%)\n\n");
        sb.append("| Status | Count |\n|---|---|\n");
        for (Status s : Status.values()) {
            sb.append("| `").append(s).append("` | ").append(counts.get(s)).append(" |\n");
        }
        sb.append("\n");

        // L4 field-coverage roll-up across IMPLEMENTED_OK ops.
        int totalDeclared = 0;
        int totalPresent = 0;
        int countedOps = 0;
        for (Entry e : entries) {
            if (e.fieldCoverage != null) {
                totalDeclared += e.fieldCoverage.declared();
                totalPresent += e.fieldCoverage.present();
                countedOps++;
            }
        }
        if (countedOps > 0) {
            sb.append("## Field coverage (L4)\n\n");
            sb.append("Across ").append(countedOps)
                    .append(" `IMPLEMENTED_OK` ops, Floci emits ")
                    .append(totalPresent).append(" of ").append(totalDeclared)
                    .append(" Smithy-declared top-level fields (")
                    .append(percent(totalPresent, totalDeclared)).append("%).\n\n");
        }

        for (Status s : Status.values()) {
            int c = counts.get(s);
            if (c == 0) {
                continue;
            }
            sb.append("## ").append(s).append(" (").append(c).append(")\n\n");
            for (Entry e : entries) {
                if (e.status != s) {
                    continue;
                }
                sb.append("- `").append(e.op).append("`");
                if (e.fieldCoverage != null) {
                    sb.append(" — fields ").append(e.fieldCoverage.fraction());
                    if (!e.fieldCoverage.missing().isEmpty()) {
                        sb.append(" (missing: ")
                                .append(String.join(", ", e.fieldCoverage.missing()))
                                .append(")");
                    }
                } else if (e.note != null && !e.note.isBlank()) {
                    sb.append(" — ").append(e.note);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toMarkdown());
    }

    public String shortSummary() {
        return String.format("%s: %d/%d (%s%%) implemented; ok=%d drift=%d val=%d state=%d unimpl=%d err=%d",
                title,
                implemented(), total(),
                percent(implemented(), total()),
                countBy(Status.IMPLEMENTED_OK),
                countBy(Status.IMPLEMENTED_DRIFT),
                countBy(Status.IMPLEMENTED_VALIDATION),
                countBy(Status.IMPLEMENTED_STATE),
                countBy(Status.NOT_IMPLEMENTED),
                countBy(Status.ERROR));
    }

    private static String percent(int n, int d) {
        if (d == 0) {
            return "0.0";
        }
        return String.format("%.1f", 100.0 * n / d);
    }
}
