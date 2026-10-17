package io.floci.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes one coverage run as three files sharing a prefix: {@code .md} for
 * reading, {@code .json} for tooling, and {@code .tsv} (one operation per
 * line, sorted) for {@code diff} between runs or emulators.
 */
final class CoverageReport {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String label;
    private final String baseUrl;
    private final String modelsRevision;
    private final String generatedAt;
    private final List<ServiceCoverage> services;

    CoverageReport(String label, String baseUrl, String modelsRevision, String generatedAt,
                   List<ServiceCoverage> services) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.modelsRevision = modelsRevision;
        this.generatedAt = generatedAt;
        this.services = services;
    }

    void write(Path prefix) throws IOException {
        Path parent = prefix.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(Path.of(prefix + ".md"), markdown(), StandardCharsets.UTF_8);
        Files.writeString(Path.of(prefix + ".json"), JSON.writeValueAsString(json()), StandardCharsets.UTF_8);
        Files.writeString(Path.of(prefix + ".tsv"), tsv(), StandardCharsets.UTF_8);
    }

    private long total(CoverageStatus status) {
        return services.stream().mapToLong(s -> s.count(status)).sum();
    }

    private long totalOperations() {
        return services.stream().mapToLong(s -> s.operations().size()).sum();
    }

    String markdown() {
        StringBuilder sb = new StringBuilder();
        long ops = totalOperations();
        long impl = total(CoverageStatus.IMPLEMENTED);
        long ambiguous = total(CoverageStatus.AMBIGUOUS);
        long serverError = total(CoverageStatus.SERVER_ERROR);
        long foreign = total(CoverageStatus.FOREIGN_ERROR);
        List<ServiceCoverage> touched = services.stream().filter(ServiceCoverage::touched).toList();
        long touchedOps = touched.stream().mapToLong(s -> s.operations().size()).sum();
        long touchedImpl = touched.stream().mapToLong(s -> s.count(CoverageStatus.IMPLEMENTED)).sum();

        sb.append("# API coverage: ").append(label).append("\n\n");
        sb.append("- Target: `").append(baseUrl).append("`\n");
        sb.append("- Models: aws/api-models-aws `").append(modelsRevision).append("`\n");
        sb.append("- Generated: ").append(generatedAt).append("\n\n");
        sb.append("Each operation is probed once with its `@required` members and judged only on ")
                .append("dispatch: a 2xx or any AWS error other than a not-implemented signal counts ")
                .append("as implemented, unless the error is identical to the reply to a request ")
                .append("that cannot reach a handler (a made-up operation name, or a made-up path). ")
                .append("Behaviour is not checked.\n\n");

        sb.append("## Summary\n\n");
        sb.append("| | Services | Operations | Implemented | Coverage |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        sb.append("| All AWS | ").append(services.size()).append(" | ").append(ops).append(" | ")
                .append(impl).append(" | ").append(pct(impl, ops)).append(" |\n");
        sb.append("| Services with any implemented operation | ").append(touched.size()).append(" | ")
                .append(touchedOps).append(" | ").append(touchedImpl).append(" | ")
                .append(pct(touchedImpl, touchedOps)).append(" |\n\n");
        sb.append("Not counted as implemented: ").append(foreign).append(" foreign errors (an S3-only ")
                .append("error from another service, i.e. S3's catch-all route answered), ").append(ambiguous).append(" ambiguous (HEAD, or a ")
                .append("4xx other than 404 / 406 without an error type), ").append(serverError).append(" server errors, ")
                .append(total(CoverageStatus.PROBE_FAILED)).append(" probe failures.\n\n");

        List<ServiceCoverage> canaryFailed = touched.stream().filter(ServiceCoverage::canaryFailed).toList();
        if (!canaryFailed.isEmpty()) {
            sb.append("Answered a made-up operation name as if it existed, so their implemented ")
                    .append("counts are upper bounds (marked `!` below): ")
                    .append(String.join(", ", canaryFailed.stream().map(ServiceCoverage::service).toList()))
                    .append(".\n\n");
        }

        sb.append("## Services with any implemented operation\n\n");
        sb.append("| Service | Protocol | Ops | Implemented | Foreign error | Ambiguous | 5xx | Not impl. | Probe failed | Coverage |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ServiceCoverage s : touched) {
            long n = s.operations().size();
            long i = s.count(CoverageStatus.IMPLEMENTED);
            sb.append("| ").append(s.service()).append(s.canaryFailed() ? " `!`" : "").append(" | ").append(String.join(", ", s.protocols()))
                    .append(" | ").append(n)
                    .append(" | ").append(i)
                    .append(" | ").append(s.count(CoverageStatus.FOREIGN_ERROR))
                    .append(" | ").append(s.count(CoverageStatus.AMBIGUOUS))
                    .append(" | ").append(s.count(CoverageStatus.SERVER_ERROR))
                    .append(" | ").append(s.count(CoverageStatus.NOT_IMPLEMENTED))
                    .append(" | ").append(s.count(CoverageStatus.PROBE_FAILED))
                    .append(" | ").append(pct(i, n)).append(" |\n");
        }

        List<ServiceCoverage> untouched = services.stream().filter(s -> !s.touched()).toList();
        sb.append("\n## Services with no implemented operation (").append(untouched.size()).append(")\n\n");
        sb.append(String.join(", ", untouched.stream().map(ServiceCoverage::service).toList())).append("\n");
        return sb.toString();
    }

    private ObjectNode json() {
        ObjectNode root = JSON.createObjectNode();
        root.put("label", label);
        root.put("baseUrl", baseUrl);
        root.put("modelsRevision", modelsRevision);
        root.put("generatedAt", generatedAt);
        ObjectNode totals = root.putObject("totals");
        totals.put("services", services.size());
        totals.put("operations", totalOperations());
        for (CoverageStatus st : CoverageStatus.values()) {
            totals.put(st.name(), total(st));
        }
        ArrayNode arr = root.putArray("services");
        for (ServiceCoverage s : services) {
            ObjectNode sn = arr.addObject();
            sn.put("service", s.service());
            sn.put("sdkId", s.sdkId());
            ArrayNode protocols = sn.putArray("protocols");
            s.protocols().forEach(protocols::add);
            sn.put("canary", s.canary() == null ? null : s.canary().name());
            ObjectNode counts = sn.putObject("counts");
            for (CoverageStatus st : CoverageStatus.values()) {
                counts.put(st.name(), s.count(st));
            }
            ArrayNode opsNode = sn.putArray("operations");
            for (OperationCoverage o : s.operations()) {
                ObjectNode on = opsNode.addObject();
                on.put("operation", o.operation());
                on.put("status", o.status().name());
                on.put("protocol", o.protocol());
                on.put("httpStatus", o.httpStatus());
                on.put("errorType", o.errorType());
            }
        }
        return root;
    }

    String tsv() {
        StringBuilder sb = new StringBuilder("service\toperation\tstatus\tprotocol\thttp\terror\n");
        for (ServiceCoverage s : services) {
            for (OperationCoverage o : s.operations()) {
                sb.append(o.service()).append('\t').append(o.operation()).append('\t')
                        .append(o.status()).append('\t').append(o.protocol()).append('\t')
                        .append(o.httpStatus()).append('\t')
                        .append(o.errorType() == null ? "" : o.errorType().replaceAll("\\s+", " "))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }
}
