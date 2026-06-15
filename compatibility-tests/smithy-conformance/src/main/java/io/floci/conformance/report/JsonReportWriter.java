package io.floci.conformance.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Renders the results as deterministic JSON suitable for baseline storage and
 * line-for-line diffs. Object keys are sorted alphabetically; cases are sorted
 * by (operation, generator).
 *
 * <p>Schema:
 * <pre>
 * {
 *   "meta": { "serviceShapeId": "...", "modelVersion": "...", "generatedAt": "..." },
 *   "summary": { "totalCases": N, "byVerdict": { "PASS": M, ... } },
 *   "operations": [
 *     { "name": "...", "byVerdict": { ... }, "cases": [ ... ] }
 *   ]
 * }
 * </pre>
 */
public final class JsonReportWriter implements ReportWriter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Override
    public void write(ReportMeta meta, List<VariantResult> results, Writer out) throws IOException {
        ObjectNode root = NODES.objectNode();
        root.set("meta", buildMeta(meta));
        root.set("summary", buildSummary(results));
        root.set("operations", buildOperations(results));
        MAPPER.writeValue(out, root);
    }

    private ObjectNode buildMeta(ReportMeta meta) {
        ObjectNode n = NODES.objectNode();
        n.put("serviceShapeId", meta.serviceShapeId());
        n.put("modelVersion", meta.modelVersion());
        n.put("generatedAt", meta.generatedAt());
        return n;
    }

    private ObjectNode buildSummary(List<VariantResult> results) {
        ObjectNode n = NODES.objectNode();
        n.put("totalCases", results.size());
        n.set("byVerdict", verdictCounts(results));
        return n;
    }

    private ArrayNode buildOperations(List<VariantResult> results) {
        Map<String, List<VariantResult>> byOp = new TreeMap<>(
                results.stream().collect(Collectors.groupingBy(r -> r.variant().operationName())));
        ArrayNode arr = NODES.arrayNode();
        for (var entry : byOp.entrySet()) {
            ObjectNode op = NODES.objectNode();
            op.put("name", entry.getKey());
            op.set("byVerdict", verdictCounts(entry.getValue()));
            op.set("cases", buildCases(entry.getValue()));
            arr.add(op);
        }
        return arr;
    }

    private ArrayNode buildCases(List<VariantResult> results) {
        List<VariantResult> sorted = results.stream()
                .sorted(Comparator.comparing((VariantResult r) -> r.variant().generator()))
                .toList();
        ArrayNode arr = NODES.arrayNode();
        for (VariantResult r : sorted) {
            ObjectNode c = NODES.objectNode();
            c.put("generator", r.variant().generator());
            c.put("verdict", r.verdict().name());
            c.put("httpStatus", r.httpStatus());
            if (r.errorType() != null) {
                c.put("errorType", r.errorType());
            }
            if (r.detail() != null) {
                c.put("detail", r.detail());
            }
            arr.add(c);
        }
        return arr;
    }

    private ObjectNode verdictCounts(List<VariantResult> results) {
        Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
        for (VariantResult r : results) {
            counts.merge(r.verdict(), 1L, Long::sum);
        }
        ObjectNode n = NODES.objectNode();
        // Iterate verdicts in declaration order, emit only non-zero keys.
        for (Verdict v : Verdict.values()) {
            long c = counts.getOrDefault(v, 0L);
            if (c > 0) {
                n.put(v.name(), c);
            }
        }
        return n;
    }
}
