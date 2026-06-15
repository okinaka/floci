package io.floci.conformance.baseline;

import io.floci.conformance.model.VariantResult;
import io.floci.conformance.model.Verdict;
import io.floci.conformance.report.ReportMeta;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Captured snapshot of what every (operation, generator) case returned on a
 * known-good run. Used by {@link BaselineDiff} as the reference point for
 * regression detection.
 *
 * <p>{@code cases} is shaped {@code op -> generator -> verdict}, sorted at
 * both levels so JSON serialization is byte-stable.
 */
public record Baseline(ReportMeta meta, Map<String, Map<String, Verdict>> cases) {

    public Baseline {
        cases = makeImmutableSorted(cases);
    }

    /** Roll up a list of variant results into a baseline. */
    public static Baseline from(ReportMeta meta, List<VariantResult> results) {
        Map<String, Map<String, Verdict>> map = new TreeMap<>();
        for (VariantResult r : results) {
            map.computeIfAbsent(r.variant().operationName(), k -> new TreeMap<>())
                    .put(r.variant().generator(), r.verdict());
        }
        return new Baseline(meta, map);
    }

    /** Total recorded cases across all operations. */
    public int totalCases() {
        return cases.values().stream().mapToInt(Map::size).sum();
    }

    private static Map<String, Map<String, Verdict>> makeImmutableSorted(
            Map<String, Map<String, Verdict>> in) {
        TreeMap<String, Map<String, Verdict>> sorted = new TreeMap<>();
        for (var e : in.entrySet()) {
            sorted.put(e.getKey(), Collections.unmodifiableMap(new TreeMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(sorted);
    }
}
