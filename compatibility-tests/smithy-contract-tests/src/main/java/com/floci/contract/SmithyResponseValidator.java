package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.JsonNameTrait;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a JSON response tree against a Smithy {@link StructureShape} and reports
 * shape drift. Used by contract tests to verify Floci wire responses against the
 * AWS-official Smithy service model.
 *
 * <p>Reported drift categories:
 * <ul>
 *   <li>required member missing</li>
 *   <li>type mismatch (e.g. Smithy says boolean, JSON has a string)</li>
 *   <li>enum value out of declared range</li>
 *   <li>unknown member (Floci emits a field the Smithy structure doesn't declare)</li>
 * </ul>
 *
 * <p>Strict-by-default: every member of the Smithy structure must be present unless
 * the Smithy {@code required} trait is absent. Unknown members are always reported.
 */
public final class SmithyResponseValidator {

    private final Model model;

    public SmithyResponseValidator(Model model) {
        this.model = model;
    }

    public List<ValidationError> validate(JsonNode actual, ShapeId structureId) {
        List<ValidationError> errors = new ArrayList<>();
        Shape shape = model.expectShape(structureId);
        walk(actual, shape, "$", errors);
        return errors;
    }

    private void walk(JsonNode actual, Shape shape, String path, List<ValidationError> errors) {
        switch (shape.getType()) {
            case STRUCTURE -> walkStructure(actual, (StructureShape) shape, path, errors);
            case LIST -> walkList(actual, (ListShape) shape, path, errors);
            case MAP -> walkMap(actual, (MapShape) shape, path, errors);
            case BOOLEAN -> requireBoolean(actual, path, errors);
            case STRING -> requireString(actual, shape, path, errors);
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL ->
                    requireNumber(actual, path, errors);
            case TIMESTAMP -> requireTimestamp(actual, path, errors);
            case BLOB -> requireString(actual, shape, path, errors); // base64 over JSON
            case ENUM, INT_ENUM -> walkEnum(actual, shape, path, errors);
            case UNION -> walkStructure(actual, (StructureShape) shape, path, errors); // union members are optional
            case DOCUMENT -> { /* any JSON shape allowed */ }
            default -> errors.add(new ValidationError(path,
                    "unhandled shape type " + shape.getType() + " for " + shape.getId()));
        }
    }

    private void walkStructure(JsonNode actual, StructureShape shape, String path,
                               List<ValidationError> errors) {
        if (!actual.isObject()) {
            errors.add(new ValidationError(path,
                    "expected object (Smithy structure " + shape.getId() + "), got " + actual.getNodeType()));
            return;
        }
        Set<String> knownMembers = new LinkedHashSet<>();
        for (MemberShape member : shape.getAllMembers().values()) {
            String name = wireName(member);
            knownMembers.add(name);
            JsonNode value = actual.get(name);
            boolean required = member.hasTrait(RequiredTrait.class);
            if (value == null || value.isNull()) {
                if (required) {
                    errors.add(new ValidationError(path + "." + name,
                            "required member missing"));
                }
                continue;
            }
            Shape target = model.expectShape(member.getTarget());
            walk(value, target, path + "." + name, errors);
        }
        actual.fieldNames().forEachRemaining(name -> {
            if (!knownMembers.contains(name)) {
                errors.add(new ValidationError(path + "." + name,
                        "unknown member (not in Smithy structure " + shape.getId() + ")"));
            }
        });
    }

    private void walkList(JsonNode actual, ListShape shape, String path,
                          List<ValidationError> errors) {
        if (!actual.isArray()) {
            errors.add(new ValidationError(path, "expected array, got " + actual.getNodeType()));
            return;
        }
        Shape memberTarget = model.expectShape(shape.getMember().getTarget());
        for (int i = 0; i < actual.size(); i++) {
            walk(actual.get(i), memberTarget, path + "[" + i + "]", errors);
        }
    }

    private void walkMap(JsonNode actual, MapShape shape, String path,
                         List<ValidationError> errors) {
        if (!actual.isObject()) {
            errors.add(new ValidationError(path, "expected object (Smithy map), got " + actual.getNodeType()));
            return;
        }
        Shape valueTarget = model.expectShape(shape.getValue().getTarget());
        actual.fields().forEachRemaining(entry ->
                walk(entry.getValue(), valueTarget, path + "[" + entry.getKey() + "]", errors));
    }

    private void walkEnum(JsonNode actual, Shape shape, String path,
                          List<ValidationError> errors) {
        if (!actual.isTextual()) {
            errors.add(new ValidationError(path,
                    "expected string enum (" + shape.getId() + "), got " + actual.getNodeType()));
            return;
        }
        String value = actual.asText();
        EnumTrait enumTrait = shape.getTrait(EnumTrait.class).orElse(null);
        if (enumTrait != null) {
            Set<String> allowed = new LinkedHashSet<>(enumTrait.getEnumDefinitionValues());
            if (!allowed.contains(value)) {
                errors.add(new ValidationError(path,
                        "enum value '" + value + "' not in declared set " + allowed));
            }
        }
    }

    private static void requireBoolean(JsonNode actual, String path, List<ValidationError> errors) {
        if (!actual.isBoolean()) {
            errors.add(new ValidationError(path, "expected boolean, got " + actual.getNodeType()));
        }
    }

    private static void requireString(JsonNode actual, Shape shape, String path,
                                      List<ValidationError> errors) {
        if (!actual.isTextual()) {
            errors.add(new ValidationError(path,
                    "expected string (" + shape.getId() + "), got " + actual.getNodeType()));
        }
    }

    private static void requireNumber(JsonNode actual, String path, List<ValidationError> errors) {
        if (!actual.isNumber()) {
            errors.add(new ValidationError(path, "expected number, got " + actual.getNodeType()));
        }
    }

    private static void requireTimestamp(JsonNode actual, String path, List<ValidationError> errors) {
        if (!actual.isNumber() && !actual.isTextual()) {
            errors.add(new ValidationError(path,
                    "expected timestamp (number epoch or ISO-8601 string), got " + actual.getNodeType()));
        }
    }

    /**
     * Resolve the JSON wire name for a Smithy member: honors {@code @jsonName} trait,
     * falling back to the member's declared name (which the SES v2 model already uses
     * in PascalCase — matching AWS REST JSON convention).
     */
    private static String wireName(MemberShape member) {
        return member.getTrait(JsonNameTrait.class)
                .map(JsonNameTrait::getValue)
                .orElse(member.getMemberName());
    }

    public record ValidationError(String path, String message) {
        @Override
        public String toString() {
            return path + ": " + message;
        }
    }
}
