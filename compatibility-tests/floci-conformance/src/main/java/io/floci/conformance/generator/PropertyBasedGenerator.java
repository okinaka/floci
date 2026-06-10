package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.RangeTrait;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Seeded fuzz: generates N random-but-valid inputs per operation. "Valid" here
 * means within Smithy {@code @length} / {@code @range} bounds where declared,
 * and using arbitrary ASCII / numbers otherwise.
 *
 * <p>Seed is derived from the operation ID so successive runs against the same
 * model produce the same variants — the fuzz is deterministic in baseline
 * terms, just diverse across operations.
 *
 * <p>Honours {@code @pattern} by falling back to the {@link InputSynthesizer}
 * placeholder for that member rather than risking a random string that doesn't
 * satisfy the regex.
 */
public final class PropertyBasedGenerator implements Generator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int VARIANTS_PER_OP = 5;
    private static final int MAX_DEPTH = 4;
    private static final String SYNTHETIC = "cov-probe-x";

    @Override
    public String name() {
        return "property-based";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        long seed = op.getId().toString().hashCode();
        Random rng = new Random(seed);
        List<GeneratedCase> cases = new ArrayList<>();
        for (int i = 0; i < VARIANTS_PER_OP; i++) {
            ObjectNode input = (ObjectNode) buildStruct(struct, model, rng, 0, new HashSet<>());
            cases.add(new GeneratedCase(
                    op,
                    "property-based.seed-" + i,
                    input,
                    ExpectedOutcome.SUCCESS,
                    null));
        }
        return cases.stream();
    }

    private JsonNode buildShape(Shape shape, Model model, Random rng, int depth,
                                Set<ShapeId> visiting, MemberShape owner) {
        if (depth > MAX_DEPTH) {
            return NODES.nullNode();
        }
        return switch (shape.getType()) {
            case STRUCTURE -> buildStruct((StructureShape) shape, model, rng, depth, visiting);
            case UNION -> buildFirstUnion((software.amazon.smithy.model.shapes.UnionShape) shape, model, rng, depth, visiting);
            case LIST, SET -> buildList((ListShape) shape, model, rng, depth, visiting, owner);
            case MAP -> buildMap((MapShape) shape, model, rng, depth, visiting, owner);
            case ENUM -> pickEnumValue((EnumShape) shape, rng);
            case STRING -> randomString(shape, owner, rng);
            case BLOB -> NODES.textNode(java.util.Base64.getEncoder()
                    .encodeToString(new byte[1 + rng.nextInt(8)]));
            case BOOLEAN -> NODES.booleanNode(rng.nextBoolean());
            case BYTE, SHORT, INTEGER -> NODES.numberNode(randomInt(shape, owner, rng));
            case LONG -> NODES.numberNode((long) randomInt(shape, owner, rng));
            case FLOAT -> NODES.numberNode(rng.nextFloat() * 100);
            case DOUBLE -> NODES.numberNode(rng.nextDouble() * 100);
            case BIG_INTEGER, BIG_DECIMAL -> NODES.numberNode(randomInt(shape, owner, rng));
            case TIMESTAMP -> NODES.numberNode(1577836800L + rng.nextInt(86400 * 365));
            case DOCUMENT -> NODES.objectNode();
            default -> NODES.nullNode();
        };
    }

    private JsonNode buildStruct(StructureShape struct, Model model, Random rng,
                                 int depth, Set<ShapeId> visiting) {
        ObjectNode node = NODES.objectNode();
        if (!visiting.add(struct.getId())) {
            return node;
        }
        try {
            for (MemberShape m : struct.getAllMembers().values()) {
                // Skip optionals with 50% probability to vary structure shape.
                if (!isRequiredOrLabel(m) && rng.nextBoolean()) {
                    continue;
                }
                Shape target = model.expectShape(m.getTarget());
                node.set(m.getMemberName(), buildShape(target, model, rng, depth + 1, visiting, m));
            }
        } finally {
            visiting.remove(struct.getId());
        }
        return node;
    }

    private JsonNode buildFirstUnion(software.amazon.smithy.model.shapes.UnionShape union, Model model, Random rng,
                                     int depth, Set<ShapeId> visiting) {
        ObjectNode node = NODES.objectNode();
        var members = new ArrayList<>(union.getAllMembers().values());
        if (members.isEmpty()) {
            return node;
        }
        MemberShape pick = members.get(rng.nextInt(members.size()));
        Shape target = model.expectShape(pick.getTarget());
        node.set(pick.getMemberName(), buildShape(target, model, rng, depth + 1, visiting, pick));
        return node;
    }

    private JsonNode buildList(ListShape list, Model model, Random rng,
                               int depth, Set<ShapeId> visiting, MemberShape owner) {
        ArrayNode arr = NODES.arrayNode();
        int size = 1 + rng.nextInt(3);
        Shape element = model.expectShape(list.getMember().getTarget());
        for (int i = 0; i < size; i++) {
            arr.add(buildShape(element, model, rng, depth + 1, visiting, list.getMember()));
        }
        return arr;
    }

    private JsonNode buildMap(MapShape map, Model model, Random rng,
                              int depth, Set<ShapeId> visiting, MemberShape owner) {
        ObjectNode m = NODES.objectNode();
        Shape value = model.expectShape(map.getValue().getTarget());
        int size = 1 + rng.nextInt(2);
        for (int i = 0; i < size; i++) {
            m.set("k" + i, buildShape(value, model, rng, depth + 1, visiting, map.getValue()));
        }
        return m;
    }

    private static JsonNode pickEnumValue(EnumShape enumShape, Random rng) {
        var values = new ArrayList<>(enumShape.getEnumValues().values());
        if (values.isEmpty()) {
            return NODES.textNode(SYNTHETIC);
        }
        return NODES.textNode(values.get(rng.nextInt(values.size())));
    }

    private static JsonNode randomString(Shape shape, MemberShape owner, Random rng) {
        // Pattern constraints are hard to satisfy randomly — fall back to placeholder.
        if (shape.hasTrait(PatternTrait.class)
                || (owner != null && owner.hasTrait(PatternTrait.class))) {
            return NODES.textNode(SYNTHETIC);
        }
        LengthTrait len = effectiveLength(shape, owner);
        int min = len != null && len.getMin().isPresent() ? len.getMin().get().intValue() : 1;
        int max = len != null && len.getMax().isPresent()
                ? Math.min(len.getMax().get().intValue(), 32)
                : 16;
        int n = min + (max > min ? rng.nextInt(max - min) : 0);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return NODES.textNode(sb.toString());
    }

    private static int randomInt(Shape shape, MemberShape owner, Random rng) {
        RangeTrait range = shape.getTrait(RangeTrait.class)
                .orElse(owner != null ? owner.getTrait(RangeTrait.class).orElse(null) : null);
        int min = range != null && range.getMin().isPresent()
                ? range.getMin().get().intValue() : 0;
        int max = range != null && range.getMax().isPresent()
                ? Math.min(range.getMax().get().intValue(), 1_000) : 1_000;
        if (max <= min) {
            return min;
        }
        return min + rng.nextInt(max - min);
    }

    private static LengthTrait effectiveLength(Shape shape, MemberShape owner) {
        return shape.getTrait(LengthTrait.class)
                .orElse(owner != null ? owner.getTrait(LengthTrait.class).orElse(null) : null);
    }

    private static boolean isRequiredOrLabel(MemberShape m) {
        return m.hasTrait(RequiredTrait.class) || m.hasTrait(HttpLabelTrait.class);
    }
}
