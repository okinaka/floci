package io.floci.conformance;

import io.floci.conformance.model.VariantResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs each service's suite at most once per JVM and hands the same results to
 * every test that asks for them.
 *
 * <p>{@link ReportingRunTest} and {@link BaselineGateTest} both need a full run
 * of the same service. Running it twice sends the second pass to an emulator
 * that still holds the first pass's state, and some of that state is not
 * salted. The Smithy model examples use literal values, and the SES v1 seeder
 * turns the {@code PutIdentityPolicy} example's {@code Identity=example.com}
 * into a {@code VerifyEmailIdentity} call. That leaves {@code example.com} a
 * verified identity, which turns every synthesized
 * {@code cov-probe-<salt>@example.com} sender into a verified one. The second
 * pass then reports different verdicts from the first, so the gate's diff
 * disagreed with the JSON report written by the same {@code mvn test}.
 * Sharing one run keeps the report and the gate on identical results.
 *
 * <p>Services share emulator state with each other too (SES v1 and v2 use one
 * identity store), so both test classes pin one service order with
 * {@code @Order}, and the services run in that order whichever class runs
 * first. It is the order the 2026-10-03 reports were produced in.
 */
final class SuiteRuns {

    private static final Map<String, List<VariantResult>> RESULTS = new ConcurrentHashMap<>();

    private SuiteRuns() {
    }

    /** The results for {@code serviceShapeId}, running {@code run} only on the first request. */
    static List<VariantResult> of(String serviceShapeId, Supplier<List<VariantResult>> run) {
        return RESULTS.computeIfAbsent(serviceShapeId, id -> List.copyOf(run.get()));
    }
}
