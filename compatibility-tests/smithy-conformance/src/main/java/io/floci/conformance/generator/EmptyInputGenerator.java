package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpLabelTrait;

import java.util.stream.Stream;

/**
 * Simplest generator: emit one case per operation with an empty input
 * (only {@code @httpLabel} members seeded with a synthetic placeholder, since
 * those are required for URL routing — without them the request would 404
 * before reaching any handler logic).
 *
 * <p>Acts as the L1-presence probe in the coverage report.
 */
public final class EmptyInputGenerator implements Generator {

    private static final String SYNTHETIC = "cov-probe-x";

    @Override
    public String name() {
        return "empty.passthrough";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        Shape inputShape = model.expectShape(op.getInputShape());
        if (inputShape instanceof StructureShape struct) {
            for (MemberShape m : struct.getAllMembers().values()) {
                if (m.hasTrait(HttpLabelTrait.class)) {
                    input.put(m.getMemberName(), SYNTHETIC);
                }
            }
        }
        return Stream.of(new GeneratedCase(op, name(), input, ExpectedOutcome.SUCCESS, null));
    }
}
