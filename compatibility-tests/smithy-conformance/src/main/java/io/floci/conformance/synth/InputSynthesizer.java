package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.RangeTrait;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Builds synthetic JSON-tree inputs from a Smithy structure. Recursion is
 * depth-limited so cyclic types don't blow up.
 *
 * <p>The two control hooks are:
 * <ul>
 *   <li>{@code memberFilter} — which members of each structure to emit
 *       (e.g. required-only).
 *   <li>{@code valueOverride} — substitute a custom JsonNode for a given member
 *       instead of the default synthetic value (used by EnumExhaust and
 *       Negative to inject specific or invalid values).
 * </ul>
 */
public final class InputSynthesizer {

    private static final int MAX_DEPTH = 4;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Model model;
    private final Predicate<MemberShape> memberFilter;
    private final BiFunction<MemberShape, Shape, JsonNode> valueOverride;

    public InputSynthesizer(Model model,
                            Predicate<MemberShape> memberFilter,
                            BiFunction<MemberShape, Shape, JsonNode> valueOverride) {
        this.model = model;
        this.memberFilter = memberFilter == null ? m -> true : memberFilter;
        this.valueOverride = valueOverride == null ? (m, s) -> null : valueOverride;
    }

    /** Predicate that keeps only {@code @required} members. */
    public static Predicate<MemberShape> requiredOnly() {
        return m -> m.hasTrait(RequiredTrait.class);
    }

    /** Predicate that keeps every member. */
    public static Predicate<MemberShape> allMembers() {
        return m -> true;
    }

    /** Build a tree for the operation's input shape (or empty object if {@code Unit}). */
    public ObjectNode synthesizeInput(StructureShape input) {
        return (ObjectNode) buildStruct(input, 0, new HashSet<>());
    }

    private JsonNode buildShape(Shape shape, int depth, Set<ShapeId> visiting, MemberShape owner) {
        if (depth > MAX_DEPTH) {
            return NODES.nullNode();
        }
        return switch (shape.getType()) {
            case STRUCTURE -> buildStruct((StructureShape) shape, depth, visiting);
            case LIST, SET -> buildList((ListShape) shape, depth, visiting);
            case MAP -> buildMap((MapShape) shape, depth, visiting);
            case ENUM -> NODES.textNode(firstEnumValue((EnumShape) shape));
            case STRING -> NODES.textNode(FormatHints.stringFor(owner));
            // Blobs get a minimal valid MIME message (base64). Plausible bytes
            // for any blob, and it unlocks AWS-valid success paths for
            // email-payload members (e.g. SendRawEmail's RawMessage.Data, where
            // the MIME From:/To: headers stand in for omitted optional params).
            case BLOB -> NODES.textNode(
                    "RnJvbTogY292LXByb2JlQGV4YW1wbGUuY29tDQpUbzogY292LXByb2JlQGV4YW1wbGUuY29tDQpTdWJqZWN0OiBjb3YtcHJvYmUNCg0KY292LXByb2JlLXgNCg==");
            case INTEGER, SHORT, BYTE -> NODES.numberNode((int) clampLong(shape, owner, 1));
            case LONG -> NODES.numberNode(clampLong(shape, owner, 1L));
            case FLOAT -> NODES.numberNode((float) clampDouble(shape, owner, 1.0d));
            case DOUBLE -> NODES.numberNode(clampDouble(shape, owner, 1.0d));
            case BIG_INTEGER, BIG_DECIMAL -> NODES.numberNode((int) clampLong(shape, owner, 1));
            case BOOLEAN -> NODES.booleanNode(false);
            case TIMESTAMP -> NODES.numberNode(1577836800L); // 2020-01-01T00:00:00Z epoch
            case DOCUMENT -> NODES.objectNode();
            case UNION -> buildFirstUnionBranch((software.amazon.smithy.model.shapes.UnionShape) shape, depth, visiting);
            default -> NODES.nullNode();
        };
    }

    /**
     * Clamps the default numeric value into the member's {@code @range} so the
     * synthesized input is in-range rather than rejected for an out-of-bounds
     * value (e.g. SESv2 {@code MaxDeliverySeconds} requires {@code >= 300}). A
     * value already within range is left untouched, keeping baseline churn
     * minimal. Member-level {@code @range} wins over the target shape's.
     */
    private static long clampLong(Shape shape, MemberShape owner, long base) {
        RangeTrait range = effectiveRange(shape, owner);
        if (range == null) {
            return base;
        }
        long v = base;
        if (range.getMin().isPresent()) {
            v = Math.max(v, range.getMin().get().longValue());
        }
        if (range.getMax().isPresent()) {
            v = Math.min(v, range.getMax().get().longValue());
        }
        return v;
    }

    private static double clampDouble(Shape shape, MemberShape owner, double base) {
        RangeTrait range = effectiveRange(shape, owner);
        if (range == null) {
            return base;
        }
        double v = base;
        if (range.getMin().isPresent()) {
            v = Math.max(v, range.getMin().get().doubleValue());
        }
        if (range.getMax().isPresent()) {
            v = Math.min(v, range.getMax().get().doubleValue());
        }
        return v;
    }

    private static RangeTrait effectiveRange(Shape shape, MemberShape owner) {
        if (owner != null && owner.hasTrait(RangeTrait.class)) {
            return owner.expectTrait(RangeTrait.class);
        }
        return shape == null ? null : shape.getTrait(RangeTrait.class).orElse(null);
    }

    private JsonNode buildStruct(StructureShape struct, int depth, Set<ShapeId> visiting) {
        ObjectNode node = NODES.objectNode();
        if (!visiting.add(struct.getId())) {
            return node;
        }
        try {
            for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
                MemberShape member = e.getValue();
                if (!memberFilter.test(member)) {
                    continue;
                }
                Shape target = model.expectShape(member.getTarget());
                JsonNode override = valueOverride.apply(member, target);
                if (override != null) {
                    node.set(e.getKey(), override);
                } else {
                    node.set(e.getKey(), buildShape(target, depth + 1, visiting, member));
                }
            }
        } finally {
            visiting.remove(struct.getId());
        }
        return node;
    }

    private JsonNode buildList(ListShape list, int depth, Set<ShapeId> visiting) {
        ArrayNode arr = NODES.arrayNode();
        Shape element = model.expectShape(list.getMember().getTarget());
        arr.add(buildShape(element, depth + 1, visiting, list.getMember()));
        return arr;
    }

    private JsonNode buildMap(MapShape map, int depth, Set<ShapeId> visiting) {
        ObjectNode m = NODES.objectNode();
        Shape value = model.expectShape(map.getValue().getTarget());
        m.set("cov-probe-key", buildShape(value, depth + 1, visiting, map.getValue()));
        return m;
    }

    private JsonNode buildFirstUnionBranch(software.amazon.smithy.model.shapes.UnionShape union,
                                           int depth, Set<ShapeId> visiting) {
        ObjectNode node = NODES.objectNode();
        union.getAllMembers().values().stream().findFirst().ifPresent(m -> {
            Shape target = model.expectShape(m.getTarget());
            node.set(m.getMemberName(), buildShape(target, depth + 1, visiting, m));
        });
        return node;
    }

    public static String firstEnumValue(EnumShape enumShape) {
        return enumShape.getEnumValues().values().stream().findFirst().orElse("cov-probe-x");
    }
}
