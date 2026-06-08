package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2 helper. Given an operation's Smithy input shape, builds a minimally-valid
 * request whose only purpose is to clear the AWS-style input-validation gate
 * ({@code InvalidParameterValue} / {@code BadRequestException}). The probe does NOT
 * try to be semantically meaningful — it just fills every Smithy-required member
 * with a type-appropriate synthetic value.
 *
 * <p>Currently supports AWS Query (form-encoded, returned as a {@link Map}). REST
 * JSON / JSON 1.1 are out of scope for this initial implementation.
 */
public final class MinimalRequestBuilder {

    /** Synthetic string value used for every required string member. */
    public static final String SYNTHETIC = "cov-probe-x";

    private final Model model;

    public MinimalRequestBuilder(Model model) {
        this.model = model;
    }

    /**
     * Build form-encoded parameters for the AWS Query protocol. Returns an empty map
     * if the operation has no input or no required members.
     */
    public Map<String, String> buildQueryForm(OperationShape op) {
        ShapeId inputId = op.getInputShape();
        if (inputId.equals(ShapeId.from("smithy.api#Unit"))) {
            return Collections.emptyMap();
        }
        StructureShape input = model.expectShape(inputId, StructureShape.class);
        Map<String, String> out = new LinkedHashMap<>();
        appendRequired(input, "", out);
        return out;
    }

    /**
     * Build a JSON request body for REST-JSON / JSON 1.1 protocols. Skips input
     * members bound to URI / query / header (those are sent out-of-band by the
     * caller), and emits all remaining required members. Returns {@code null} if
     * the operation has no input shape, signalling the caller to send no body.
     */
    public JsonNode buildJsonBody(OperationShape op) {
        ShapeId inputId = op.getInputShape();
        if (inputId.equals(ShapeId.from("smithy.api#Unit"))) {
            return null;
        }
        StructureShape input = model.expectShape(inputId, StructureShape.class);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        fillJson(input, root, /* respectHttpBindings= */ true);
        return root;
    }

    /**
     * Resolve path parameters from the operation's input shape. For every member
     * tagged with {@code @httpLabel} the synthetic value is returned under that
     * member name. Callers (REST-JSON probes) substitute these into the URI
     * template.
     */
    public Map<String, String> resolvePathParams(OperationShape op) {
        ShapeId inputId = op.getInputShape();
        if (inputId.equals(ShapeId.from("smithy.api#Unit"))) {
            return Collections.emptyMap();
        }
        StructureShape input = model.expectShape(inputId, StructureShape.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (MemberShape member : input.getAllMembers().values()) {
            if (!member.hasTrait(HttpLabelTrait.class)) {
                continue;
            }
            Shape target = model.expectShape(member.getTarget());
            String synthetic = scalarOrNull(target);
            out.put(member.getMemberName(), synthetic != null ? synthetic : SYNTHETIC);
        }
        return out;
    }

    private void fillJson(StructureShape struct, ObjectNode out, boolean respectHttpBindings) {
        for (MemberShape member : struct.getAllMembers().values()) {
            if (!member.hasTrait(RequiredTrait.class)) {
                continue;
            }
            if (respectHttpBindings
                    && (member.hasTrait(HttpLabelTrait.class)
                        || member.hasTrait(HttpQueryTrait.class)
                        || member.hasTrait(HttpHeaderTrait.class))) {
                continue;
            }
            String name = member.getMemberName();
            Shape target = model.expectShape(member.getTarget());
            switch (target.getType()) {
                case STRING -> out.put(name, SYNTHETIC);
                case BOOLEAN -> out.put(name, true);
                case BYTE, SHORT, INTEGER, LONG -> out.put(name, 1);
                case FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL -> out.put(name, 1.0);
                case TIMESTAMP -> out.put(name, "2026-01-01T00:00:00Z");
                case ENUM -> out.put(name, firstEnumValue((EnumShape) target));
                case INT_ENUM -> out.put(name, 1);
                case STRUCTURE -> {
                    ObjectNode nested = out.putObject(name);
                    fillJson((StructureShape) target, nested, false);  // nested struct is body-only
                }
                case LIST -> out.putArray(name);  // empty array
                case MAP -> out.putObject(name);  // empty map
                default -> {
                    if (target.hasTrait(EnumTrait.class)) {
                        EnumTrait t = target.expectTrait(EnumTrait.class);
                        t.getEnumDefinitionValues().stream().findFirst()
                                .ifPresentOrElse(v -> out.put(name, v),
                                                 () -> out.put(name, SYNTHETIC));
                    }
                }
            }
        }
    }

    private void appendRequired(StructureShape struct, String prefix, Map<String, String> out) {
        for (MemberShape member : struct.getAllMembers().values()) {
            if (!member.hasTrait(RequiredTrait.class)) {
                continue;
            }
            String name = prefix.isEmpty() ? member.getMemberName()
                                            : prefix + "." + member.getMemberName();
            Shape target = model.expectShape(member.getTarget());
            String synthetic = scalarOrNull(target);
            if (synthetic != null) {
                out.put(name, synthetic);
            } else if (target instanceof StructureShape nested) {
                appendRequired(nested, name, out);
            }
            // Lists, maps, blobs are left empty — the AWS Query rule "X.member.1=..."
            // can be added if a probe surfaces a required-non-empty-list case.
        }
    }

    private static String scalarOrNull(Shape target) {
        return switch (target.getType()) {
            case STRING -> SYNTHETIC;
            case BOOLEAN -> "true";
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL -> "1";
            case TIMESTAMP -> "2026-01-01T00:00:00Z";
            case ENUM -> firstEnumValue((EnumShape) target);
            case INT_ENUM -> "1";
            default -> {
                // Some models still use the legacy @enum trait on a STRING shape.
                if (target.hasTrait(EnumTrait.class)) {
                    EnumTrait t = target.expectTrait(EnumTrait.class);
                    yield t.getEnumDefinitionValues().stream().findFirst().orElse(SYNTHETIC);
                }
                yield null;
            }
        };
    }

    private static String firstEnumValue(EnumShape enumShape) {
        return enumShape.getEnumValues().keySet().stream().findFirst().orElse(SYNTHETIC);
    }
}
