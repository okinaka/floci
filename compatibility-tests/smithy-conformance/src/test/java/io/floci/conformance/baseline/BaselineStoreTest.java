package io.floci.conformance.baseline;

import io.floci.conformance.model.Verdict;
import io.floci.conformance.report.ReportMeta;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link BaselineStore}: JSON round-trip fidelity,
 * byte-for-byte deterministic serialization, and alphabetical key ordering —
 * the properties the committed {@code baselines/*.json} diffs rely on.
 */
class BaselineStoreTest {

    private static Baseline sampleBaseline() {
        Map<String, Map<String, Verdict>> cases = new LinkedHashMap<>();
        cases.computeIfAbsent("ZebraOp", k -> new LinkedHashMap<>()).put("g1", Verdict.PASS);
        cases.computeIfAbsent("AlphaOp", k -> new LinkedHashMap<>()).put("g2", Verdict.FAIL_5XX);
        cases.computeIfAbsent("AlphaOp", k -> new LinkedHashMap<>()).put("g1", Verdict.PASS);
        return new Baseline(
                new ReportMeta("svc", "v1", "2026-06-09T00:00:00Z"),
                cases);
    }

    @Test
    void roundtrip_preserves_data() throws Exception {
        Baseline original = sampleBaseline();
        StringWriter w = new StringWriter();
        BaselineStore.write(w, original);
        Baseline reloaded = BaselineStore.read(new StringReader(w.toString()));

        assertThat(reloaded.meta()).isEqualTo(original.meta());
        assertThat(reloaded.cases()).isEqualTo(original.cases());
    }

    @Test
    void serialization_is_deterministic() throws Exception {
        Baseline b = sampleBaseline();
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        BaselineStore.write(w1, b);
        BaselineStore.write(w2, b);
        assertThat(w1.toString()).isEqualTo(w2.toString());
    }

    @Test
    void output_sorts_op_keys_alphabetically() throws Exception {
        StringWriter w = new StringWriter();
        BaselineStore.write(w, sampleBaseline());
        String json = w.toString();
        int alpha = json.indexOf("AlphaOp");
        int zebra = json.indexOf("ZebraOp");
        assertThat(alpha).isPositive();
        assertThat(zebra).isGreaterThan(alpha);
    }
}
