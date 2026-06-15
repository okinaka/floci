package io.floci.conformance.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.Verdict;
import io.floci.conformance.report.ReportMeta;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serializes {@link Baseline}s as deterministic JSON. Same baseline → same
 * bytes, so commit diffs only show real changes.
 */
public final class BaselineStore {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private BaselineStore() {
    }

    public static void writeFile(Path file, Baseline baseline) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(w, baseline);
        }
    }

    public static void write(Writer out, Baseline baseline) throws IOException {
        ObjectNode root = NODES.objectNode();
        ObjectNode meta = NODES.objectNode();
        meta.put("serviceShapeId", baseline.meta().serviceShapeId());
        meta.put("modelVersion", baseline.meta().modelVersion());
        meta.put("capturedAt", baseline.meta().generatedAt());
        root.set("meta", meta);

        ObjectNode casesNode = NODES.objectNode();
        for (var opEntry : baseline.cases().entrySet()) {
            ObjectNode opNode = NODES.objectNode();
            for (var gen : opEntry.getValue().entrySet()) {
                opNode.put(gen.getKey(), gen.getValue().name());
            }
            casesNode.set(opEntry.getKey(), opNode);
        }
        root.set("cases", casesNode);
        MAPPER.writeValue(out, root);
    }

    public static Baseline readFile(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    public static Baseline read(Reader in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        JsonNode meta = root.path("meta");
        ReportMeta reportMeta = new ReportMeta(
                meta.path("serviceShapeId").asText(),
                meta.path("modelVersion").asText(),
                meta.path("capturedAt").asText());

        Map<String, Map<String, Verdict>> cases = new TreeMap<>();
        JsonNode casesNode = root.path("cases");
        casesNode.fields().forEachRemaining(opEntry -> {
            Map<String, Verdict> opCases = new TreeMap<>();
            opEntry.getValue().fields().forEachRemaining(genEntry ->
                    opCases.put(genEntry.getKey(), Verdict.valueOf(genEntry.getValue().asText())));
            cases.put(opEntry.getKey(), opCases);
        });
        return new Baseline(reportMeta, cases);
    }
}
