package io.floci.conformance.baseline;

import io.floci.conformance.model.Verdict;
import io.floci.conformance.report.ReportMeta;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineDiffTest {

    private static final ReportMeta META = new ReportMeta("svc", "ver", "2026-06-09T00:00:00Z");

    private static Baseline build(Object... opGenVerdict) {
        Map<String, Map<String, Verdict>> cases = new LinkedHashMap<>();
        for (int i = 0; i < opGenVerdict.length; i += 3) {
            String op = (String) opGenVerdict[i];
            String gen = (String) opGenVerdict[i + 1];
            Verdict v = (Verdict) opGenVerdict[i + 2];
            cases.computeIfAbsent(op, k -> new LinkedHashMap<>()).put(gen, v);
        }
        return new Baseline(META, cases);
    }

    @Test
    void identical_baselines_have_empty_diff() {
        Baseline a = build("Op", "g1", Verdict.PASS);
        Baseline b = build("Op", "g1", Verdict.PASS);
        BaselineDiff diff = BaselineDiff.compute(a, b);
        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.hasRegressions()).isFalse();
    }

    @Test
    void pass_to_fail_is_regression() {
        Baseline base = build("Op", "g1", Verdict.PASS);
        Baseline cur = build("Op", "g1", Verdict.FAIL_SILENT_PASS);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.regressions()).hasSize(1);
        assertThat(diff.regressions().get(0).operation()).isEqualTo("Op");
        assertThat(diff.hasRegressions()).isTrue();
    }

    @Test
    void inconclusive_to_fail_is_regression() {
        Baseline base = build("Op", "g1", Verdict.INCONCLUSIVE_VALIDATION);
        Baseline cur = build("Op", "g1", Verdict.FAIL_5XX);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.regressions()).hasSize(1);
    }

    @Test
    void fail_to_pass_is_improvement() {
        Baseline base = build("Op", "g1", Verdict.FAIL_SILENT_PASS);
        Baseline cur = build("Op", "g1", Verdict.PASS);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.improvements()).hasSize(1);
        assertThat(diff.regressions()).isEmpty();
    }

    @Test
    void pass_to_inconclusive_is_drift_not_regression() {
        Baseline base = build("Op", "g1", Verdict.PASS);
        Baseline cur = build("Op", "g1", Verdict.INCONCLUSIVE_STATE);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.drifts()).hasSize(1);
        assertThat(diff.regressions()).isEmpty();
    }

    @Test
    void inconclusive_swaps_are_drifts() {
        Baseline base = build("Op", "g1", Verdict.INCONCLUSIVE_VALIDATION);
        Baseline cur = build("Op", "g1", Verdict.INCONCLUSIVE_STATE);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.drifts()).hasSize(1);
        assertThat(diff.regressions()).isEmpty();
    }

    @Test
    void new_case_only_in_current() {
        Baseline base = build("Op", "g1", Verdict.PASS);
        Baseline cur = build("Op", "g1", Verdict.PASS, "Op", "g2", Verdict.PASS);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.newCases()).hasSize(1);
        assertThat(diff.newCases().get(0).generator()).isEqualTo("g2");
    }

    @Test
    void missing_case_only_in_baseline() {
        Baseline base = build("Op", "g1", Verdict.PASS, "Op", "g2", Verdict.PASS);
        Baseline cur = build("Op", "g1", Verdict.PASS);
        BaselineDiff diff = BaselineDiff.compute(base, cur);
        assertThat(diff.missingCases()).hasSize(1);
        assertThat(diff.missingCases().get(0).generator()).isEqualTo("g2");
    }

    @Test
    void gate_blocks_on_regression() {
        Baseline base = build("Op", "g1", Verdict.PASS);
        Baseline cur = build("Op", "g1", Verdict.FAIL_5XX);
        BaselineGate.Result r = BaselineGate.check(BaselineDiff.compute(base, cur));
        assertThat(r.passed()).isFalse();
        assertThat(r.summary()).contains("regression");
    }

    @Test
    void gate_passes_with_only_improvements() {
        Baseline base = build("Op", "g1", Verdict.FAIL_SILENT_PASS);
        Baseline cur = build("Op", "g1", Verdict.PASS);
        BaselineGate.Result r = BaselineGate.check(BaselineDiff.compute(base, cur));
        assertThat(r.passed()).isTrue();
        assertThat(r.summary()).contains("improvement");
    }

    @Test
    void markdown_lists_each_section() {
        Baseline base = build(
                "OpA", "g1", Verdict.PASS,
                "OpB", "g1", Verdict.FAIL_SILENT_PASS);
        Baseline cur = build(
                "OpA", "g1", Verdict.FAIL_5XX,        // regression
                "OpB", "g1", Verdict.PASS,            // improvement
                "OpC", "g1", Verdict.PASS);           // new case
        String md = BaselineDiff.compute(base, cur).toMarkdown();
        assertThat(md).contains("Regressions (1)");
        assertThat(md).contains("Improvements (1)");
        assertThat(md).contains("New cases (1)");
    }
}
