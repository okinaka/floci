package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs {@code Create<X>} / {@code Put<X>} / {@code Add<X>} setup ops with the
 * matching {@code Get<X>} verify op, by exact resource-name suffix match. For
 * each pair where setup and verify share at least one top-level input member,
 * emits a scenario that writes synthetic data then reads it back.
 *
 * <p>The shared member becomes the identifier (its synthetic value links the
 * two calls). All other top-level setup-input fields that also exist on the
 * verify response shape are added to {@code echoedPaths}, so the runner can
 * verify each one round-trips.
 *
 * <p>Cases where the identifier lives inside a nested struct (e.g.
 * {@code CreateConfigurationSet} taking {@code ConfigurationSet.Name}) are
 * skipped — this generator handles flat-name pairs only.
 */
public final class RoundTripEchoGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> SETUP_PREFIXES = List.of("Create", "Put", "Add");
    private static final String VERIFY_PREFIX = "Get";

    public List<RoundTripScenario> generate(ServiceShape service, Model model) {
        Map<String, OperationShape> byName = new LinkedHashMap<>();
        for (ShapeId opId : service.getAllOperations()) {
            OperationShape op = model.expectShape(opId, OperationShape.class);
            byName.put(op.getId().getName(), op);
        }
        List<RoundTripScenario> out = new ArrayList<>();
        for (OperationShape setupOp : byName.values()) {
            String resource = trimSetupPrefix(setupOp.getId().getName());
            if (resource == null) {
                continue;
            }
            OperationShape verifyOp = byName.get(VERIFY_PREFIX + resource);
            if (verifyOp == null) {
                continue;
            }
            StructureShape setupInputShape = ScenarioSupport.inputStruct(setupOp, model);
            StructureShape verifyInputShape = ScenarioSupport.inputStruct(verifyOp, model);
            if (setupInputShape == null || verifyInputShape == null) {
                continue;
            }
            Set<String> shared = ScenarioSupport.sharedTopLevelMembers(setupInputShape, verifyInputShape);
            if (shared.isEmpty()) {
                continue;
            }

            // Synthesize the full setup input, then override the shared identifier
            // with a unique, deterministic placeholder so consecutive scenarios
            // against a fresh container don't collide.
            ObjectNode setupInput = new InputSynthesizer(
                    model, InputSynthesizer.allMembers(), null).synthesizeInput(setupInputShape);
            String identifierValue = "cov-probe-rt-" + resource;
            for (String memberName : shared) {
                setupInput.set(memberName, NODES.textNode(identifierValue));
            }

            ObjectNode verifyInput = NODES.objectNode();
            for (String memberName : shared) {
                verifyInput.set(memberName, NODES.textNode(identifierValue));
            }

            List<String> echoedPaths = ScenarioSupport.echoedFields(setupInputShape, verifyOp, model);
            if (echoedPaths.isEmpty()) {
                continue;
            }

            out.add(new RoundTripScenario(
                    "round-trip-echo." + resource,
                    verifyOp,
                    setupOp,
                    setupInput,
                    verifyOp,
                    verifyInput,
                    echoedPaths));
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
