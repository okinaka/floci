package io.floci.conformance.baseline;

import io.floci.conformance.model.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Computes a per-case diff between two {@link Baseline}s and bins the
 * differences by direction so callers can act on each independently:
 *
 * <ul>
 *   <li>{@link #regressions()} — previously PASS/INCONCLUSIVE, now a real
 *       {@code FAIL_*}. These block the gate.
 *   <li>{@link #improvements()} — previously {@code FAIL_*}, now PASS.
 *   <li>{@link #drifts()} — verdict changed in a way that's neither
 *       improvement nor regression (e.g. PASS → INCONCLUSIVE_STATE,
 *       INCONCLUSIVE_VALIDATION → INCONCLUSIVE_STATE). Informational; often
 *       reflects test-ordering flakiness, not Floci behavior.
 *   <li>{@link #newCases()} — case exists in current but not baseline (model
 *       grew or a generator started producing it).
 *   <li>{@link #missingCases()} — case in baseline but not current (model
 *       shrank or generator stopped producing).
 * </ul>
 */
public record BaselineDiff(
        List<Change> regressions,
        List<Change> improvements,
        List<Change> drifts,
        List<Change> newCases,
        List<Change> missingCases) {

    public record Change(String operation, String generator,
                         Verdict baselineVerdict, Verdict currentVerdict) {
        /** A stable key for sort / grep. */
        public String fqcn() {
            return operation + "::" + generator;
        }
    }

    public boolean hasRegressions() {
        return !regressions.isEmpty();
    }

    public boolean isEmpty() {
        return regressions.isEmpty() && improvements.isEmpty() && drifts.isEmpty()
                && newCases.isEmpty() && missingCases.isEmpty();
    }

    public static BaselineDiff compute(Baseline baseline, Baseline current) {
        List<Change> regressions = new ArrayList<>();
        List<Change> improvements = new ArrayList<>();
        List<Change> drifts = new ArrayList<>();
        List<Change> newCases = new ArrayList<>();
        List<Change> missingCases = new ArrayList<>();

        // All op names from either side, sorted.
        TreeSet<String> allOps = new TreeSet<>();
        allOps.addAll(baseline.cases().keySet());
        allOps.addAll(current.cases().keySet());

        for (String op : allOps) {
            Map<String, Verdict> bGens = baseline.cases().getOrDefault(op, Map.of());
            Map<String, Verdict> cGens = current.cases().getOrDefault(op, Map.of());
            TreeSet<String> allGens = new TreeSet<>();
            allGens.addAll(bGens.keySet());
            allGens.addAll(cGens.keySet());

            for (String gen : allGens) {
                Verdict bV = bGens.get(gen);
                Verdict cV = cGens.get(gen);
                Change change = new Change(op, gen, bV, cV);
                if (bV == null) {
                    newCases.add(change);
                } else if (cV == null) {
                    missingCases.add(change);
                } else if (bV == cV) {
                    // unchanged, skip
                } else if (isRealFailure(cV) && !isRealFailure(bV)) {
                    regressions.add(change);
                } else if (cV == Verdict.PASS && isRealFailure(bV)) {
                    improvements.add(change);
                } else {
                    drifts.add(change);
                }
            }
        }

        Comparator<Change> byFqcn = Comparator.comparing(Change::fqcn);
        regressions.sort(byFqcn);
        improvements.sort(byFqcn);
        drifts.sort(byFqcn);
        newCases.sort(byFqcn);
        missingCases.sort(byFqcn);

        return new BaselineDiff(
                List.copyOf(regressions),
                List.copyOf(improvements),
                List.copyOf(drifts),
                List.copyOf(newCases),
                List.copyOf(missingCases));
    }

    private static boolean isRealFailure(Verdict v) {
        return switch (v) {
            case FAIL_SHAPE, FAIL_SILENT_PASS, FAIL_4XX_UNROUTED,
                    FAIL_WRONG_ERROR_TYPE, FAIL_5XX, HARNESS_ERROR -> true;
            default -> false;
        };
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Baseline Diff\n\n");
        if (isEmpty()) {
            sb.append("_No changes from baseline._\n");
            return sb.toString();
        }
        section(sb, "Regressions", regressions, "These previously passed and now fail. **Gate blocks merge.**");
        section(sb, "Improvements", improvements, "Previously failing, now passing — bump the baseline.");
        section(sb, "Drifts", drifts, "Verdict changed but not pass↔fail. Often state-ordering flakiness.");
        section(sb, "New cases", newCases, "Generator started producing these.");
        section(sb, "Missing cases", missingCases, "Generator stopped producing these.");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<Change> changes, String hint) {
        sb.append("## ").append(title).append(" (").append(changes.size()).append(")\n\n");
        sb.append("_").append(hint).append("_\n\n");
        if (changes.isEmpty()) {
            sb.append("_None._\n\n");
            return;
        }
        for (Change c : changes) {
            String bv = c.baselineVerdict() == null ? "—" : "`" + c.baselineVerdict().name() + "`";
            String cv = c.currentVerdict() == null ? "—" : "`" + c.currentVerdict().name() + "`";
            sb.append("- `").append(c.operation()).append("` — ").append(c.generator())
                    .append(": ").append(bv).append(" → ").append(cv).append('\n');
        }
        sb.append('\n');
    }
}
