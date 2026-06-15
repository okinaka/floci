package io.floci.conformance.baseline;

/**
 * Decision policy on top of a {@link BaselineDiff}.
 *
 * <p>The default policy is strict on regressions and permissive on everything
 * else: the gate fails only when at least one previously-passing case now
 * fails. Improvements / drifts / new / missing cases are surfaced for human
 * review but don't block.
 */
public final class BaselineGate {

    private BaselineGate() {
    }

    public record Result(boolean passed, String summary, BaselineDiff diff) {
    }

    public static Result check(BaselineDiff diff) {
        if (diff.hasRegressions()) {
            return new Result(
                    false,
                    diff.regressions().size() + " regression(s) — see Baseline Diff",
                    diff);
        }
        if (diff.isEmpty()) {
            return new Result(true, "No changes from baseline.", diff);
        }
        return new Result(
                true,
                "OK — "
                        + diff.improvements().size() + " improvement(s), "
                        + diff.drifts().size() + " drift(s), "
                        + diff.newCases().size() + " new, "
                        + diff.missingCases().size() + " missing",
                diff);
    }
}
