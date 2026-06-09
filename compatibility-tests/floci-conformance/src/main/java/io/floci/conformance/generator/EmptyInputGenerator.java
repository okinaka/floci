package io.floci.conformance.generator;

import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpLabelTrait;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Simplest possible generator: emit one variant per operation with no input
 * payload. Useful as a smoke test (does Floci route the operation at all?) and
 * as the L1-presence probe in the coverage report.
 *
 * <p>For REST operations with required {@code @httpLabel} members, substitutes
 * a synthetic placeholder so the URL is well-formed; otherwise the request
 * never reaches the controller.
 */
public final class EmptyInputGenerator implements Generator {

    private static final String SYNTHETIC = "cov-probe-x";

    @Override
    public String name() {
        return "empty.passthrough";
    }

    @Override
    public Stream<Variant> generate(OperationShape op, Model model) {
        Map<String, String> pathParams = resolvePathLabels(op, model);
        return Stream.of(new Variant(
                op,
                name(),
                pathParams,
                Map.of(),
                Map.of(),
                null,
                ExpectedOutcome.SUCCESS,
                null));
    }

    private static Map<String, String> resolvePathLabels(OperationShape op, Model model) {
        if (op.getInputShape().toString().equals("smithy.api#Unit")) {
            return Map.of();
        }
        Shape input = model.expectShape(op.getInputShape());
        if (!(input instanceof StructureShape struct)) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (MemberShape m : struct.getAllMembers().values()) {
            if (m.hasTrait(HttpLabelTrait.class)) {
                labels.put(m.getMemberName(), SYNTHETIC);
            }
        }
        return labels.isEmpty() ? Map.of() : new HashMap<>(labels);
    }
}
