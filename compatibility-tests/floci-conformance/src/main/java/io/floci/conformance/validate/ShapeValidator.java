package io.floci.conformance.validate;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks an HTTP response body (already parsed as a Jackson {@link JsonNode})
 * against a Smithy structure and reports type / enum / required-field issues.
 *
 * <p>{@code xmlMode} adjusts for awsQuery's XML quirks:
 * <ul>
 *   <li>An XML empty element ({@code <Foo/>}) parses as the empty string
 *       {@code ""}. Treat as empty list / map / struct depending on the
 *       expected shape.
 *   <li>String elements that hold numeric content parse as text; allow them
 *       to satisfy a numeric expected shape.
 *   <li>Wrapped lists ({@code <Identities><member>...</member></Identities>})
 *       parse as {@code {"Identities": {"member": [...]}}}; tolerate that
 *       single-key indirection on the list path.
 * </ul>
 *
 * <p>{@link Result#ok()} means every {@code @required} member was present and
 * every typed value parsed cleanly. Optional missing members are not flagged.
 */
public final class ShapeValidator {

    public record Issue(String path, String message) {
    }

    public record Result(List<Issue> issues) {
        public boolean ok() {
            return issues.isEmpty();
        }
    }

    private final Model model;
    private final boolean xmlMode;

    public ShapeValidator(Model model, boolean xmlMode) {
        this.model = model;
        this.xmlMode = xmlMode;
    }

    public Result validate(JsonNode root, StructureShape output) {
        List<Issue> issues = new ArrayList<>();
        walkStruct(root, output, "$", issues);
        return new Result(issues);
    }

    /**
     * Strip awsQuery's wrapper. {@code XmlMapper.readTree} already consumes the
     * root {@code <FooResponse>} element, so the typical input here is
     * {@code {FooResult: {…}, ResponseMetadata: {…}}}; dive into
     * {@code FooResult} when present.
     */
    public static JsonNode unwrapXmlResult(JsonNode root, String operationName) {
        if (root == null || !root.isObject()) {
            return root;
        }
        JsonNode result = root.path(operationName + "Result");
        if (result.isObject()) {
            return result;
        }
        // Older Smithy XML decoders preserve the outer Response wrapper.
        JsonNode resp = root.path(operationName + "Response");
        if (resp.isObject()) {
            JsonNode nested = resp.path(operationName + "Result");
            return nested.isObject() ? nested : resp;
        }
        return root;
    }

    private void walkStruct(JsonNode node, StructureShape struct, String path, List<Issue> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (hasRequired(struct)) {
                out.add(new Issue(path, "expected object, got null"));
            }
            return;
        }
        if (xmlMode && node.isTextual() && node.asText().isEmpty()) {
            // <Foo/> for an empty struct is fine if there are no @required members.
            for (MemberShape m : struct.getAllMembers().values()) {
                if (m.hasTrait(RequiredTrait.class)) {
                    out.add(new Issue(path + "." + m.getMemberName(), "missing @required"));
                }
            }
            return;
        }
        if (!node.isObject()) {
            out.add(new Issue(path, "expected object, got " + nodeTypeOf(node)));
            return;
        }
        for (MemberShape m : struct.getAllMembers().values()) {
            JsonNode value = node.get(m.getMemberName());
            String mPath = path + "." + m.getMemberName();
            if (value == null || value.isNull() || value.isMissingNode()) {
                if (m.hasTrait(RequiredTrait.class)) {
                    out.add(new Issue(mPath, "missing @required"));
                }
                continue;
            }
            Shape target = model.expectShape(m.getTarget());
            walkValue(value, target, mPath, out);
        }
    }

    private void walkValue(JsonNode value, Shape shape, String path, List<Issue> out) {
        switch (shape.getType()) {
            case STRUCTURE, UNION -> {
                if (shape instanceof StructureShape s) {
                    walkStruct(value, s, path, out);
                }
            }
            case LIST, SET -> walkList(value, (ListShape) shape, path, out);
            case MAP -> walkMap(value, (MapShape) shape, path, out);
            case ENUM -> walkEnum(value, (EnumShape) shape, path, out);
            case STRING, BLOB, DOCUMENT -> {
                // Anything textual is acceptable.
                if (!value.isTextual() && !(xmlMode && value.isObject() && value.size() == 0)) {
                    if (!value.isNumber() && !value.isBoolean()) {
                        out.add(new Issue(path, "expected string, got " + nodeTypeOf(value)));
                    }
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    if (xmlMode && value.isTextual()
                            && ("true".equals(value.asText()) || "false".equals(value.asText()))) {
                        // OK — XML scalar.
                    } else {
                        out.add(new Issue(path, "expected boolean, got " + nodeTypeOf(value)));
                    }
                }
            }
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL -> {
                if (!value.isNumber()) {
                    if (xmlMode && value.isTextual() && isNumeric(value.asText())) {
                        // OK — XML numeric-as-text.
                    } else {
                        out.add(new Issue(path, "expected number, got " + nodeTypeOf(value)));
                    }
                }
            }
            case TIMESTAMP -> {
                // Accept numbers, ISO-8601 strings, or XML scalar text.
                if (!value.isNumber() && !value.isTextual()) {
                    out.add(new Issue(path, "expected timestamp, got " + nodeTypeOf(value)));
                }
            }
            default -> {
                // Shape types we don't validate strictly — services rarely return these.
            }
        }
    }

    private void walkList(JsonNode value, ListShape list, String path, List<Issue> out) {
        if (xmlMode && value.isTextual() && value.asText().isEmpty()) {
            return; // <Items/> → empty list.
        }
        JsonNode arr = value;
        if (xmlMode && value.isObject() && value.size() == 1) {
            // <Items><member>...</member></Items> → unwrap the single inner key.
            arr = value.elements().next();
        }
        Shape element = model.expectShape(list.getMember().getTarget());
        if (!arr.isArray()) {
            if (xmlMode) {
                // XML serializes a one-item list as the single child element —
                // either an object or a scalar, depending on the element shape.
                walkValue(arr, element, path + "[0]", out);
                return;
            }
            out.add(new Issue(path, "expected array, got " + nodeTypeOf(arr)));
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            walkValue(arr.get(i), element, path + "[" + i + "]", out);
        }
    }

    private void walkMap(JsonNode value, MapShape map, String path, List<Issue> out) {
        if (xmlMode && value.isTextual() && value.asText().isEmpty()) {
            return;
        }
        if (!value.isObject()) {
            out.add(new Issue(path, "expected map (object), got " + nodeTypeOf(value)));
            return;
        }
        Shape valueShape = model.expectShape(map.getValue().getTarget());
        value.fields().forEachRemaining(e ->
                walkValue(e.getValue(), valueShape, path + "{" + e.getKey() + "}", out));
    }

    private void walkEnum(JsonNode value, EnumShape enumShape, String path, List<Issue> out) {
        if (!value.isTextual()) {
            out.add(new Issue(path, "expected enum string, got " + nodeTypeOf(value)));
            return;
        }
        String v = value.asText();
        if (!enumShape.getEnumValues().values().contains(v)) {
            out.add(new Issue(path, "enum value " + quote(v) + " not in declared set"));
        }
    }

    private static boolean hasRequired(StructureShape s) {
        return s.getAllMembers().values().stream().anyMatch(m -> m.hasTrait(RequiredTrait.class));
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            new java.math.BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String nodeTypeOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }
        return node.getNodeType().name().toLowerCase();
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
