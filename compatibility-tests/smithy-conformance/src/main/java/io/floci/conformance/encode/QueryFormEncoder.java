package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.XmlFlattenedTrait;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes the logical input as AWS Query form params with dotted member paths.
 *
 * <p>Conventions implemented:
 * <ul>
 *   <li>Scalar → {@code Name=value}.
 *   <li>Nested struct → {@code Name.SubName=…}.
 *   <li>List (default) → {@code Name.member.N=…}.
 *   <li>List (with {@code @xmlFlattened}) → {@code Name.N=…}.
 *   <li>Map (default) → {@code Name.entry.N.key=…&Name.entry.N.value=…}.
 *   <li>Map (with {@code @xmlFlattened}) → {@code Name.N.key=…&Name.N.value=…}.
 * </ul>
 *
 * <p>Walks the JsonNode in parallel with the Smithy input shape so the dotted
 * names reflect Smithy semantics — Jackson alone doesn't know "this list is
 * flattened" or "this map needs entry wrappers".
 */
public final class QueryFormEncoder implements RequestEncoder {

    private static final String PROTOCOL = "aws.protocols#awsQuery";

    private final Model model;

    public QueryFormEncoder(Model model) {
        this.model = model;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Variant encode(GeneratedCase g) {
        OperationShape op = g.operation();
        Map<String, String> params = new LinkedHashMap<>();
        JsonNode logical = g.logicalInput();
        if (logical != null && !logical.isMissingNode() && !logical.isNull()) {
            StructureShape inputShape = expectInputStructure(op);
            flattenStruct(logical, inputShape, "", params);
        }
        return new Variant(
                op,
                g.generator(),
                Map.of(),
                params,
                Map.of(),
                null,
                g.expectedOutcome(),
                g.expectedError());
    }

    private StructureShape expectInputStructure(OperationShape op) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape s)) {
            throw new IllegalStateException(
                    "Operation " + op.getId() + " input is not a structure: " + inputShape.getType());
        }
        return s;
    }

    private void flattenStruct(JsonNode node, StructureShape struct, String prefix,
                               Map<String, String> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
            JsonNode value = node.get(e.getKey());
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Shape target = model.expectShape(e.getValue().getTarget());
            flattenValue(value, target, key, e.getValue(), out);
        }
    }

    private void flattenValue(JsonNode value, Shape shape, String key,
                              MemberShape owner, Map<String, String> out) {
        switch (shape.getType()) {
            case STRUCTURE, UNION -> {
                if (shape instanceof StructureShape s) {
                    flattenStruct(value, s, key, out);
                }
            }
            case LIST, SET -> flattenList(value, (ListShape) shape, key, owner, out);
            case MAP -> flattenMap(value, (MapShape) shape, key, owner, out);
            default -> out.put(key, scalarToString(value));
        }
    }

    private void flattenList(JsonNode value, ListShape list, String key,
                             MemberShape owner, Map<String, String> out) {
        if (!value.isArray()) {
            return;
        }
        boolean flat = isFlattened(owner) || isFlattened(list);
        Shape element = model.expectShape(list.getMember().getTarget());
        int i = 1;
        for (JsonNode item : value) {
            String elemKey = flat ? key + "." + i : key + ".member." + i;
            flattenValue(item, element, elemKey, list.getMember(), out);
            i++;
        }
    }

    private void flattenMap(JsonNode value, MapShape map, String key,
                            MemberShape owner, Map<String, String> out) {
        if (!value.isObject()) {
            return;
        }
        boolean flat = isFlattened(owner) || isFlattened(map);
        Shape keyShape = model.expectShape(map.getKey().getTarget());
        Shape valShape = model.expectShape(map.getValue().getTarget());
        int i = 1;
        var iter = value.fields();
        while (iter.hasNext()) {
            var entry = iter.next();
            String base = flat ? key + "." + i : key + ".entry." + i;
            flattenValue(textOf(entry.getKey()), keyShape, base + ".key", map.getKey(), out);
            flattenValue(entry.getValue(), valShape, base + ".value", map.getValue(), out);
            i++;
        }
    }

    private static boolean isFlattened(Shape s) {
        return s.hasTrait(XmlFlattenedTrait.class);
    }

    private static String scalarToString(JsonNode value) {
        if (value.isBoolean()) {
            return value.booleanValue() ? "true" : "false";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        return value.asText();
    }

    /** Tiny shim: wrap a string key in a {@code TextNode}-shaped {@link JsonNode}. */
    private static JsonNode textOf(String s) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(s);
    }
}
