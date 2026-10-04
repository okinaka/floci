package io.floci.conformance.coverage;

import java.util.List;

/**
 * Every operation's outcome for one service.
 *
 * @param service    api-models-aws directory name.
 * @param sdkId      The model's {@code aws.api#service} sdkId.
 * @param protocols  Protocols probed, in the order tried.
 * @param canary     Status of a made-up operation name, or {@code null} when not
 *                   applicable (REST-only service). Anything but
 *                   {@code NOT_IMPLEMENTED} makes the implemented count an upper bound.
 * @param operations One entry per operation, sorted by name.
 */
public record ServiceCoverage(
        String service,
        String sdkId,
        List<String> protocols,
        CoverageStatus canary,
        List<OperationCoverage> operations) {

    /** Whether the emulator answered a made-up operation as if it existed. */
    public boolean canaryFailed() {
        return canary != null && canary != CoverageStatus.NOT_IMPLEMENTED;
    }

    public long count(CoverageStatus status) {
        return operations.stream().filter(o -> o.status() == status).count();
    }

    /** Whether at least one operation is implemented. */
    public boolean touched() {
        return count(CoverageStatus.IMPLEMENTED) > 0;
    }
}
