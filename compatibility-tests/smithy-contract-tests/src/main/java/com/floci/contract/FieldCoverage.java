package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.JsonNameTrait;
import software.amazon.smithy.model.traits.XmlNameTrait;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 — L4 field coverage. For an op that returned 200 with a shape-conformant
 * body, this asks the next question: of the Smithy-declared top-level members on
 * the output structure, how many did Floci actually emit?
 *
 * <p>Required members are checked by {@link SmithyResponseValidator}; this helper
 * looks at the broader picture (required + optional). Top-level only — nested
 * structure recursion can be added later if there's signal value.
 */
public final class FieldCoverage {

    public record Result(int declared, int present, List<String> missing) {
        public double ratio() {
            return declared == 0 ? 1.0 : (double) present / declared;
        }
        public String fraction() {
            return present + "/" + declared;
        }
    }

    private FieldCoverage() {}

    /**
     * Measure top-level field coverage of {@code actual} against the Smithy output
     * structure identified by {@code outputShape}. {@code xmlMode} selects the
     * appropriate wire-name trait (XML → {@code @xmlName}, JSON → {@code @jsonName}).
     */
    public static Result measure(JsonNode actual, ShapeId outputShape, Model model,
                                 boolean xmlMode) {
        StructureShape shape = model.expectShape(outputShape, StructureShape.class);
        int declared = 0;
        int present = 0;
        List<String> missing = new ArrayList<>();
        for (MemberShape member : shape.getAllMembers().values()) {
            declared++;
            String name = wireName(member, xmlMode);
            JsonNode value = actual.get(name);
            if (value != null && !value.isNull()
                    && !(value.isTextual() && value.asText().isEmpty())) {
                present++;
            } else {
                missing.add(name);
            }
        }
        return new Result(declared, present, missing);
    }

    private static String wireName(MemberShape member, boolean xmlMode) {
        if (xmlMode) {
            return member.getTrait(XmlNameTrait.class)
                    .map(XmlNameTrait::getValue)
                    .orElse(member.getMemberName());
        }
        return member.getTrait(JsonNameTrait.class)
                .map(JsonNameTrait::getValue)
                .orElse(member.getMemberName());
    }
}
