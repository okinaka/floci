package io.floci.conformance.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Puts the {@code .tsv} files of several coverage runs side by side, one row
 * per operation, as a Markdown table.
 *
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=io.floci.conformance.coverage.CoverageCompareMain \
 *       -Dexec.args="--service sesv2 --out target/compare-sesv2.md \
 *           floci=target/coverage-floci.tsv fakecloud=target/coverage-fakecloud.tsv"
 * </pre>
 *
 * Positional arguments are {@code label=path} pairs, in column order.
 * {@code --service} limits the table to one api-models-aws service; without
 * it, every operation of every service is listed. {@code --out} defaults to
 * standard output.
 */
public final class CoverageCompareMain {

    private CoverageCompareMain() {
    }

    /** One run's verdict for one operation. */
    private record Cell(String status, String http, String error) {
    }

    public static void main(String[] args) throws IOException {
        String service = null;
        Path out = null;
        Map<String, Path> runs = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--service" -> service = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    int eq = args[i].indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("Expected label=path.tsv, got: " + args[i]);
                    }
                    runs.put(args[i].substring(0, eq), Path.of(args[i].substring(eq + 1)));
                }
            }
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("Give at least one label=path.tsv");
        }

        // operation key ("service/Operation") -> label -> cell
        Map<String, Map<String, Cell>> table = new TreeMap<>();
        for (Map.Entry<String, Path> run : runs.entrySet()) {
            List<String> lines = Files.readAllLines(run.getValue(), StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split("\t", -1);
                if (service != null && !f[0].equals(service)) {
                    continue;
                }
                String key = service != null ? f[1] : f[0] + "/" + f[1];
                table.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .put(run.getKey(), new Cell(f[2], f[4], f[5]));
            }
        }

        String markdown = render(service, new ArrayList<>(runs.keySet()), table);
        if (out == null) {
            System.out.print(markdown);
        } else {
            Files.writeString(out, markdown, StandardCharsets.UTF_8);
            System.out.println("Wrote " + out);
        }
    }

    private static String render(String service, List<String> labels, Map<String, Map<String, Cell>> table) {
        StringBuilder sb = new StringBuilder();
        sb.append("# API coverage comparison").append(service == null ? "" : ": " + service).append("\n\n");
        sb.append("`Y` implemented, `-` not implemented, `?` undecided (status in parentheses: ")
                .append("AMBIGUOUS, SERVER_ERROR, FOREIGN_ERROR or PROBE_FAILED). ")
                .append("Rows where the runs disagree are marked `*`.\n\n");

        sb.append("| |");
        labels.forEach(l -> sb.append(' ').append(l).append(" |"));
        sb.append("\n|---|");
        labels.forEach(l -> sb.append("---:|"));
        sb.append("\n| Implemented |");
        for (String label : labels) {
            long impl = table.values().stream()
                    .filter(row -> row.containsKey(label) && row.get(label).status().equals("IMPLEMENTED"))
                    .count();
            long total = table.values().stream().filter(row -> row.containsKey(label)).count();
            sb.append(' ').append(impl).append(" / ").append(total).append(" |");
        }
        long differing = table.values().stream().filter(row -> differs(row, labels)).count();
        sb.append("\n\n").append(differing).append(" of ").append(table.size())
                .append(" operations differ between runs.\n\n");

        sb.append("| Operation |");
        labels.forEach(l -> sb.append(' ').append(l).append(" |"));
        sb.append("\n|---|");
        labels.forEach(l -> sb.append(":---:|"));
        sb.append('\n');
        for (Map.Entry<String, Map<String, Cell>> row : table.entrySet()) {
            sb.append("| ").append(row.getKey()).append(differs(row.getValue(), labels) ? " `*`" : "").append(" |");
            for (String label : labels) {
                sb.append(' ').append(symbol(row.getValue().get(label))).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean differs(Map<String, Cell> row, List<String> labels) {
        return labels.stream().map(l -> row.get(l) == null ? "" : symbol(row.get(l))).distinct().count() > 1;
    }

    private static String symbol(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.status()) {
            case "IMPLEMENTED" -> "Y";
            case "NOT_IMPLEMENTED" -> "-";
            default -> "? (" + cell.status() + " " + cell.http() + ")";
        };
    }
}
