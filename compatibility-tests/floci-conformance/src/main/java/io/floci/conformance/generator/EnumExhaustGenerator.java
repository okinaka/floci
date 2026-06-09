package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * For every top-level enum-typed input member, emit one case per enum value.
 * Catches handlers that switch on enum values and forget cases (the classic
 * "we handle Active and Inactive but not Suspended" bug).
 *
 * <p>Each case is built by synthesizing a full input (all members) and
 * overriding just the target enum member's value. Predicts
 * {@link ExpectedOutcome#SUCCESS}.
 */
public final class EnumExhaustGenerator implements Generator {

    @Override
    public String name() {
        return "enum-exhaust";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        List<GeneratedCase> cases = new ArrayList<>();
        for (MemberShape member : struct.getAllMembers().values()) {
            Shape target = model.expectShape(member.getTarget());
            if (!(target instanceof EnumShape enumShape)) {
                continue;
            }
            for (String value : enumShape.getEnumValues().values()) {
                ObjectNode input = baselineInputWithOverride(struct, model, member.getMemberName(), value);
                cases.add(new GeneratedCase(
                        op,
                        "enum-exhaust." + member.getMemberName() + "." + sanitize(value),
                        input,
                        ExpectedOutcome.SUCCESS,
                        null));
            }
        }
        return cases.stream();
    }

    private static ObjectNode baselineInputWithOverride(StructureShape struct, Model model,
                                                       String memberName, String enumValue) {
        InputSynthesizer base = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode input = base.synthesizeInput(struct);
        input.set(memberName, JsonNodeFactory.instance.textNode(enumValue));
        return input;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
