package io.floci.conformance.scenario;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared shape-introspection helpers for the scenario generators.
 */
final class ScenarioSupport {

    private ScenarioSupport() {
    }

    /**
     * The single-resource read-back op for a resource: {@code Get<X>} if it
     * exists, else {@code Describe<X>} (many services name reads {@code Describe}).
     * Returns {@code null} if neither exists.
     */
    static OperationShape readBackOp(String resource, Map<String, OperationShape> byName) {
        OperationShape get = byName.get("Get" + resource);
        return get != null ? get : byName.get("Describe" + resource);
    }

    /** The op's input as a structure, or {@code null} for Unit / non-struct inputs. */
    static StructureShape inputStruct(OperationShape op, Model model) {
        if (op.getInputShape().toString().equals("smithy.api#Unit")) {
            return null;
        }
        Shape s = model.expectShape(op.getInputShape());
        return (s instanceof StructureShape st) ? st : null;
    }

    /** Top-level member names present in both structures, in {@code a}'s order. */
    static Set<String> sharedTopLevelMembers(StructureShape a, StructureShape b) {
        Set<String> common = new LinkedHashSet<>(a.getAllMembers().keySet());
        common.retainAll(b.getAllMembers().keySet());
        return common;
    }

    /**
     * Setup-input top-level members that also appear on the verify op's output
     * structure — the fields a scenario can assert round-tripped.
     */
    static List<String> echoedFields(StructureShape setupInput,
                                     OperationShape verifyOp, Model model) {
        if (verifyOp.getOutputShape().toString().equals("smithy.api#Unit")) {
            return List.of();
        }
        Shape outShape = model.expectShape(verifyOp.getOutputShape());
        if (!(outShape instanceof StructureShape outStruct)) {
            return List.of();
        }
        Set<String> outMembers = outStruct.getAllMembers().keySet();
        List<String> echoed = new ArrayList<>();
        for (MemberShape m : setupInput.getAllMembers().values()) {
            if (outMembers.contains(m.getMemberName())) {
                echoed.add(m.getMemberName());
            }
        }
        return echoed;
    }
}
