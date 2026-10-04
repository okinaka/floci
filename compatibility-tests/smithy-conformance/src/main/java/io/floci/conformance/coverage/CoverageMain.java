package io.floci.conformance.coverage;

import software.amazon.smithy.aws.traits.ServiceTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures how much of the AWS API an emulator dispatches: every operation of
 * every service in an api-models-aws checkout, probed once. {@code coverage.sh}
 * in the module root fetches the models and runs this; to run it directly:
 *
 * <pre>
 *   mvn -q compile exec:java -Dexec.args="--models ~/.cache/floci-conformance/api-models-aws/models \
 *       --base-url http://localhost:4566 --label floci --out target/coverage-floci"
 * </pre>
 *
 * Options: {@code --models DIR} (or env {@code CONFORMANCE_MODELS_DIR}),
 * {@code --base-url URL} (or env {@code FLOCI_BASE_URL}, default
 * {@code http://localhost:4566}), {@code --label NAME}, {@code --out PREFIX}
 * (default {@code target/coverage}), {@code --services a,b,c} to probe only
 * those model directories, {@code --threads N} (default 8).
 */
public final class CoverageMain {

    private CoverageMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        Path modelsDir = Path.of(expandHome(Optional.ofNullable(opts.get("models"))
                .or(() -> Optional.ofNullable(System.getenv("CONFORMANCE_MODELS_DIR")))
                .orElseThrow(() -> new IllegalArgumentException(
                        "--models DIR (an api-models-aws checkout's models/ directory) is required"))));
        String baseUrl = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("FLOCI_BASE_URL", "http://localhost:4566"));
        String label = opts.getOrDefault("label", baseUrl);
        Path out = Path.of(opts.getOrDefault("out", "target/coverage"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));

        ModelCatalog catalog = new ModelCatalog(modelsDir);
        List<String> services = catalog.services();
        if (opts.containsKey("services")) {
            Set<String> wanted = new LinkedHashSet<>(Arrays.asList(opts.get("services").split(",")));
            services = services.stream().filter(wanted::contains).toList();
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<ServiceCoverage>>> futures = new ArrayList<>();
        for (String service : services) {
            futures.add(pool.submit(() -> probeService(catalog, service, baseUrl)));
        }
        List<ServiceCoverage> results = new ArrayList<>();
        int done = 0;
        for (Future<List<ServiceCoverage>> f : futures) {
            try {
                results.addAll(f.get());
            } catch (ExecutionException e) {
                System.err.println("coverage: " + e.getCause());
            }
            done++;
            if (done % 50 == 0) {
                System.err.printf("coverage: %d/%d services%n", done, services.size());
            }
        }
        pool.shutdown();
        results.sort(Comparator.comparing(ServiceCoverage::service));

        CoverageReport report = new CoverageReport(label, baseUrl, modelsRevision(modelsDir),
                Instant.now().toString(), results);
        report.write(out);
        String markdown = report.markdown();
        System.out.println(markdown.substring(0, markdown.indexOf("## Services with any implemented operation")));
        System.out.println("Wrote " + out + ".{md,json,tsv}");
    }

    private static List<ServiceCoverage> probeService(ModelCatalog catalog, String service, String baseUrl) {
        Optional<Path> file = catalog.modelFile(service);
        if (file.isEmpty()) {
            return List.of();
        }
        Model model = ModelCatalog.load(file.get());
        List<ServiceCoverage> result = new ArrayList<>();
        for (ServiceShape shape : model.getServiceShapes()) {
            String sdkId = shape.getTrait(ServiceTrait.class).map(ServiceTrait::getSdkId)
                    .orElse(shape.getId().getName());
            ServiceProbe probe = new ServiceProbe(service, model, shape, baseUrl);
            result.add(new ServiceCoverage(service, sdkId, probe.protocols(), probe.canary(), probe.run()));
        }
        return result;
    }

    /** The api-models-aws commit the models come from, or {@code unknown}. */
    private static String modelsRevision(Path modelsDir) {
        try {
            Process p = new ProcessBuilder("git", "-C", modelsDir.toString(), "log", "-1", "--format=%h %cs")
                    .redirectErrorStream(true).start();
            String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !outText.isEmpty() ? outText : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static String expandHome(String path) {
        return path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value, got: " + args[i]);
            }
            opts.put(args[i].substring(2), args[++i]);
        }
        return opts;
    }
}
