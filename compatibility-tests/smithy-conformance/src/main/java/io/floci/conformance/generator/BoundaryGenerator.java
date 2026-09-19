package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.RangeTrait;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Emits boundary cases for members carrying {@code @length} or {@code @range}.
 *
 * <p>For each constrained member:
 * <ul>
 *   <li>at-min value (predicts SUCCESS),
 *   <li>min-minus-one (predicts CLIENT_ERROR),
 *   <li>at-max value (predicts SUCCESS),
 *   <li>max-plus-one (predicts CLIENT_ERROR).
 * </ul>
 *
 * <p>"min-minus-one" is omitted when the lower bound is 0 (you can't go lower
 * for a length or a sane minimum); the at-min case still fires. Same for max
 * when it's unbounded.
 *
 * <p>The rest of the input is a fully-populated baseline; only the target
 * member is overridden so the case isolates the boundary.
 */
public final class BoundaryGenerator implements Generator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public String name() {
        return "boundary";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        // Constrained members are rare; synthesize the baseline lazily and only
        // once per op, then deep-copy per boundary case.
        ObjectNode[] baseline = new ObjectNode[1];
        java.util.function.Supplier<ObjectNode> baselineSupplier = () -> {
            if (baseline[0] == null) {
                baseline[0] = new InputSynthesizer(
                        model, InputSynthesizer.allMembers(), null).synthesizeInput(struct);
            }
            return baseline[0];
        };
        List<GeneratedCase> cases = new ArrayList<>();
        for (MemberShape member : struct.getAllMembers().values()) {
            Shape target = model.expectShape(member.getTarget());
            collectLengthCases(op, baselineSupplier, member, target, cases);
            collectRangeCases(op, baselineSupplier, member, target, cases);
        }
        return cases.stream();
    }

    private void collectLengthCases(OperationShape op,
                                    java.util.function.Supplier<ObjectNode> baseline,
                                    MemberShape member, Shape target, List<GeneratedCase> out) {
        LengthTrait length = member.getTrait(LengthTrait.class)
                .orElse(target.getTrait(LengthTrait.class).orElse(null));
        if (length == null) {
            return;
        }
        ShapeType type = target.getType();
        if (type != ShapeType.STRING && type != ShapeType.BLOB
                && type != ShapeType.LIST && type != ShapeType.MAP) {
            return;
        }
        length.getMin().ifPresent(min -> {
            int v = min.intValue();
            emit(op, baseline, member, lengthValue(type, v),
                    "boundary.length.min." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, out);
            // An empty @httpLabel value changes the URL structure and routes
            // to a different operation entirely (verified live: GET
            // /v2/email/templates/ with an empty TemplateName lands on
            // ListEmailTemplates and returns 200). The variant wouldn't test
            // the target op, so skip under-min when it degenerates to "".
            boolean emptyLabel = v - 1 == 0 && member.hasTrait(HttpLabelTrait.class)
                    && type == ShapeType.STRING;
            if (v > 0 && !emptyLabel) {
                emit(op, baseline, member, lengthValue(type, v - 1),
                        "boundary.length.under.min." + member.getMemberName(),
                        ExpectedOutcome.CLIENT_ERROR, out);
            }
        });
        length.getMax().ifPresent(max -> {
            int v = max.intValue();
            emit(op, baseline, member, lengthValue(type, v),
                    "boundary.length.max." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, out);
            emit(op, baseline, member, lengthValue(type, v + 1),
                    "boundary.length.over.max." + member.getMemberName(),
                    ExpectedOutcome.CLIENT_ERROR, out);
        });
    }

    private void collectRangeCases(OperationShape op,
                                   java.util.function.Supplier<ObjectNode> baseline,
                                   MemberShape member, Shape target, List<GeneratedCase> out) {
        RangeTrait range = member.getTrait(RangeTrait.class)
                .orElse(target.getTrait(RangeTrait.class).orElse(null));
        if (range == null) {
            return;
        }
        if (!isNumeric(target.getType())) {
            return;
        }
        range.getMin().ifPresent(min -> {
            emit(op, baseline, member, numberValue(target.getType(), min),
                    "boundary.range.min." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, out);
            BigDecimal below = min.subtract(BigDecimal.ONE);
            emit(op, baseline, member, numberValue(target.getType(), below),
                    "boundary.range.under.min." + member.getMemberName(),
                    ExpectedOutcome.CLIENT_ERROR, out);
        });
        range.getMax().ifPresent(max -> {
            emit(op, baseline, member, numberValue(target.getType(), max),
                    "boundary.range.max." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, out);
            BigDecimal above = max.add(BigDecimal.ONE);
            emit(op, baseline, member, numberValue(target.getType(), above),
                    "boundary.range.over.max." + member.getMemberName(),
                    ExpectedOutcome.CLIENT_ERROR, out);
        });
    }

    private static boolean isNumeric(ShapeType t) {
        return switch (t) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL -> true;
            default -> false;
        };
    }

    private static JsonNode lengthValue(ShapeType t, int n) {
        if (n < 0) {
            n = 0;
        }
        return switch (t) {
            case STRING -> NODES.textNode("a".repeat(n));
            case BLOB -> NODES.textNode(java.util.Base64.getEncoder().encodeToString(new byte[n]));
            case LIST -> {
                var arr = NODES.arrayNode();
                for (int i = 0; i < n; i++) {
                    arr.add("x");
                }
                yield arr;
            }
            case MAP -> {
                var m = NODES.objectNode();
                for (int i = 0; i < n; i++) {
                    m.put("k" + i, "v");
                }
                yield m;
            }
            default -> NODES.nullNode();
        };
    }

    private static JsonNode numberValue(ShapeType t, BigDecimal v) {
        return switch (t) {
            case BYTE, SHORT, INTEGER -> NODES.numberNode(v.intValueExact());
            case LONG -> NODES.numberNode(v.longValueExact());
            case FLOAT -> NODES.numberNode(v.floatValue());
            case DOUBLE -> NODES.numberNode(v.doubleValue());
            case BIG_INTEGER, BIG_DECIMAL -> NODES.numberNode(v);
            default -> NODES.nullNode();
        };
    }

    private static void emit(OperationShape op, java.util.function.Supplier<ObjectNode> baseline,
                             MemberShape member, JsonNode value,
                             String generatorName, ExpectedOutcome outcome, List<GeneratedCase> out) {
        ObjectNode input = baseline.get().deepCopy();
        input.set(member.getMemberName(), value);
        out.add(new GeneratedCase(op, generatorName, input, outcome, null));
    }
}
