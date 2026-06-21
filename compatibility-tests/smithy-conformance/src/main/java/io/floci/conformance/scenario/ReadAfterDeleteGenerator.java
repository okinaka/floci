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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs {@code Create<X>} / {@code Put<X>} / {@code Add<X>} with the matching
 * {@code Delete<X>} and {@code Get<X>} ops (by exact resource-name suffix) into
 * a create → delete → read-back lifecycle scenario. Only fires when a single
 * flat identifier member is shared by all three inputs, so the same synthetic
 * id links every step.
 */
public final class ReadAfterDeleteGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> SETUP_PREFIXES = List.of("Create", "Put", "Add");

    public List<LifecycleScenario> generate(ServiceShape service, Model model) {
        Map<String, OperationShape> byName = new LinkedHashMap<>();
        for (ShapeId opId : service.getAllOperations()) {
            OperationShape op = model.expectShape(opId, OperationShape.class);
            byName.put(op.getId().getName(), op);
        }
        List<LifecycleScenario> out = new ArrayList<>();
        for (OperationShape createOp : byName.values()) {
            String resource = trimSetupPrefix(createOp.getId().getName());
            if (resource == null) {
                continue;
            }
            OperationShape deleteOp = byName.get("Delete" + resource);
            OperationShape readOp = ScenarioSupport.readBackOp(resource, byName);
            if (deleteOp == null || readOp == null) {
                continue;
            }
            StructureShape createIn = ScenarioSupport.inputStruct(createOp, model);
            StructureShape deleteIn = ScenarioSupport.inputStruct(deleteOp, model);
            StructureShape readIn = ScenarioSupport.inputStruct(readOp, model);
            if (createIn == null || deleteIn == null || readIn == null) {
                continue;
            }
            // Identifier member(s) shared by all three inputs.
            Set<String> shared = new LinkedHashSet<>(
                    ScenarioSupport.sharedTopLevelMembers(createIn, deleteIn));
            shared.retainAll(readIn.getAllMembers().keySet());
            if (shared.isEmpty()) {
                continue;
            }

            String idValue = "cov-probe-rad-" + resource;
            ObjectNode createInput = new InputSynthesizer(
                    model, InputSynthesizer.allMembers(), null).synthesizeInput(createIn);
            ObjectNode deleteInput = NODES.objectNode();
            ObjectNode readInput = NODES.objectNode();
            for (String member : shared) {
                createInput.set(member, NODES.textNode(idValue));
                deleteInput.set(member, NODES.textNode(idValue));
                readInput.set(member, NODES.textNode(idValue));
            }

            out.add(new LifecycleScenario(
                    "read-after-delete." + resource,
                    readOp, createOp, createInput, deleteOp, deleteInput, readInput));
        }
        return out;
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
