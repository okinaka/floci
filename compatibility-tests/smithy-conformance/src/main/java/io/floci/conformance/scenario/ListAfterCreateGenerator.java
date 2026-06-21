package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs {@code Create<X>} / {@code Put<X>} / {@code Add<X>} with the matching
 * {@code List<Xs>} op into a create → list membership scenario. The resource's
 * flat identifier member is found by intersecting the create and {@code Get<X>}
 * inputs (the same linking id the read-after-delete check uses), so its value
 * can be searched for in the list response.
 */
public final class ListAfterCreateGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> SETUP_PREFIXES = List.of("Create", "Put", "Add");

    public List<ListAfterCreateScenario> generate(ServiceShape service, Model model) {
        Map<String, OperationShape> byName = new LinkedHashMap<>();
        for (ShapeId opId : service.getAllOperations()) {
            OperationShape op = model.expectShape(opId, OperationShape.class);
            byName.put(op.getId().getName(), op);
        }
        List<ListAfterCreateScenario> out = new ArrayList<>();
        for (OperationShape createOp : byName.values()) {
            String resource = trimSetupPrefix(createOp.getId().getName());
            if (resource == null) {
                continue;
            }
            OperationShape listOp = findListOp(resource, byName);
            OperationShape getOp = ScenarioSupport.readBackOp(resource, byName);
            if (listOp == null || getOp == null) {
                continue;
            }
            StructureShape createIn = ScenarioSupport.inputStruct(createOp, model);
            StructureShape getIn = ScenarioSupport.inputStruct(getOp, model);
            if (createIn == null || getIn == null) {
                continue;
            }
            // The identifier is the single flat member create and get share.
            Set<String> shared = ScenarioSupport.sharedTopLevelMembers(createIn, getIn);
            if (shared.size() != 1) {
                continue;
            }
            String idMember = shared.iterator().next();

            String idValue = "cov-probe-lac-" + resource;
            ObjectNode createInput = new InputSynthesizer(
                    model, InputSynthesizer.allMembers(), null).synthesizeInput(createIn);
            createInput.set(idMember, NODES.textNode(idValue));

            StructureShape listIn = ScenarioSupport.inputStruct(listOp, model);
            ObjectNode listInput = listIn == null
                    ? NODES.objectNode()
                    : new InputSynthesizer(model, InputSynthesizer.requiredOnly(), null)
                            .synthesizeInput(listIn);

            out.add(new ListAfterCreateScenario(
                    "list-after-create." + resource,
                    createOp, createInput, listOp, listInput, idMember));
        }
        return out;
    }

    /** First existing {@code List<plural>} op for the resource, trying common plural forms. */
    private static OperationShape findListOp(String resource, Map<String, OperationShape> byName) {
        for (String plural : plurals(resource)) {
            OperationShape op = byName.get("List" + plural);
            if (op != null) {
                return op;
            }
        }
        return null;
    }

    private static List<String> plurals(String r) {
        List<String> out = new ArrayList<>();
        if (r.endsWith("y") && r.length() > 1 && !isVowel(r.charAt(r.length() - 2))) {
            out.add(r.substring(0, r.length() - 1) + "ies");
        }
        if (r.endsWith("s") || r.endsWith("x") || r.endsWith("z")
                || r.endsWith("ch") || r.endsWith("sh")) {
            out.add(r + "es");
        }
        out.add(r + "s");
        out.add(r); // some lists keep the singular (e.g. ListBucket-less services)
        return out;
    }

    private static boolean isVowel(char c) {
        return "aeiouAEIOU".indexOf(c) >= 0;
    }

    /** Resource suffix when the op name starts with Create/Put/Add. */
    static String trimSetupPrefix(String opName) {
        for (String p : SETUP_PREFIXES) {
            if (opName.startsWith(p) && opName.length() > p.length()) {
                return opName.substring(p.length());
            }
        }
        return null;
    }
}
