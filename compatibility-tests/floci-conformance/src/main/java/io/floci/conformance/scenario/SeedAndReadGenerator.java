package io.floci.conformance.scenario;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.synth.FormatHints;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs setup operations ({@code Create*}, {@code Put*}, {@code Add*},
 * {@code Verify*}) with read operations ({@code Get*}, {@code Describe*},
 * {@code List*}) by shared top-level input-member name. Generates a
 * {@link RoundTripScenario} for each pair that {@link RoundTripEchoGenerator}
 * doesn't already cover by exact resource-name suffix.
 *
 * <p>The pair (setup, verify) produces a scenario only if:
 * <ul>
 *   <li>both have struct inputs;
 *   <li>they share at least one top-level member name (the identifier);
 *   <li>they aren't the same Create&lt;X&gt; / Get&lt;X&gt; pair that
 *       {@link RoundTripEchoGenerator} already emits.
 * </ul>
 *
 * <p>The shared identifier value comes from {@link FormatHints}, so e.g. an
 * {@code EmailAddress} member seeds with {@code cov-probe@example.com}, an
 * {@code *Arn} member with a synthetic ARN, etc. Setup and verify both get
 * the same value (wrapped in a single-element list when the target shape is a
 * list), so the verify call references the just-seeded resource.
 *
 * <p>Echoed paths follow the same rule as {@link RoundTripEchoGenerator}:
 * top-level members of the setup input that also appear on the verify output
 * structure. The runner asserts those round-trip.
 */
public final class SeedAndReadGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> SETUP_PREFIXES = List.of("Create", "Put", "Add", "Verify");
    private static final List<String> VERIFY_PREFIXES = List.of("Get", "Describe", "List");

    public List<RoundTripScenario> generate(ServiceShape service, Model model) {
        Map<String, OperationShape> ops = new LinkedHashMap<>();
        for (ShapeId id : service.getAllOperations()) {
            OperationShape op = model.expectShape(id, OperationShape.class);
            ops.put(op.getId().getName(), op);
        }

        // The pair loop is O(setup_ops × verify_ops); synthesize each op's
        // input once and deep-copy per pair instead of re-walking the shape
        // graph hundreds of times.
        InputSynthesizer synth = new InputSynthesizer(
                model, InputSynthesizer.allMembers(), null);
        Map<String, ObjectNode> synthCache = new LinkedHashMap<>();

        List<RoundTripScenario> out = new ArrayList<>();
        for (OperationShape setup : ops.values()) {
            String setupPrefix = findPrefix(setup.getId().getName(), SETUP_PREFIXES);
            if (setupPrefix == null) {
                continue;
            }
            String setupResource = setup.getId().getName().substring(setupPrefix.length());
            StructureShape setupInputShape = ScenarioSupport.inputStruct(setup, model);
            if (setupInputShape == null) {
                continue;
            }

            for (OperationShape verify : ops.values()) {
                if (verify == setup) {
                    continue;
                }
                String verifyPrefix = findPrefix(verify.getId().getName(), VERIFY_PREFIXES);
                if (verifyPrefix == null) {
                    continue;
                }
                String verifyResource = verify.getId().getName().substring(verifyPrefix.length());
                StructureShape verifyInputShape = ScenarioSupport.inputStruct(verify, model);
                if (verifyInputShape == null) {
                    continue;
                }

                // Skip the exact Create<X>/Get<X> pair — RoundTripEcho owns it.
                if (setupPrefix.equals("Create") && verifyPrefix.equals("Get")
                        && setupResource.equals(verifyResource)) {
                    continue;
                }

                Set<String> shared = ScenarioSupport.sharedTopLevelMembers(setupInputShape, verifyInputShape);
                if (shared.isEmpty()) {
                    continue;
                }
                // Echoed paths are optional for seed-and-read: even if no
                // setup-input member appears on the verify output, a successful
                // 2xx with a valid response shape still demonstrates that the
                // verify op can read the just-seeded resource.
                List<String> echoedPaths = ScenarioSupport.echoedFields(setupInputShape, verify, model);

                // Both inputs are fully synthesized so REST path templates get
                // all their @httpLabel substitutions; then the shared
                // identifiers are overridden to a common seed value so verify
                // references the resource setup just created.
                ObjectNode setupInput = synthCache.computeIfAbsent(
                        setup.getId().getName(), k -> synth.synthesizeInput(setupInputShape))
                        .deepCopy();
                ObjectNode verifyInput = synthCache.computeIfAbsent(
                        verify.getId().getName(), k -> synth.synthesizeInput(verifyInputShape))
                        .deepCopy();

                for (String memberName : shared) {
                    MemberShape verifyMember = verifyInputShape.getAllMembers().get(memberName);
                    String seedValue = FormatHints.stringFor(verifyMember);
                    injectIdentifier(setupInput, setupInputShape, memberName, seedValue, model);
                    injectIdentifier(verifyInput, verifyInputShape, memberName, seedValue, model);
                }

                String label = "seed-and-read." + setup.getId().getName()
                        + "_to_" + verify.getId().getName();
                out.add(new RoundTripScenario(
                        label, verify, setup, setupInput, verify, verifyInput, echoedPaths));
            }
        }
        return out;
    }

    static String findPrefix(String opName, List<String> prefixes) {
        for (String p : prefixes) {
            if (opName.startsWith(p) && opName.length() > p.length()) {
                return p;
            }
        }
        return null;
    }



    private static void injectIdentifier(ObjectNode target, StructureShape struct,
                                         String memberName, String seedValue, Model model) {
        MemberShape member = struct.getAllMembers().get(memberName);
        if (member == null) {
            return;
        }
        Shape targetShape = model.expectShape(member.getTarget());
        if (targetShape.getType() == ShapeType.LIST) {
            ArrayNode arr = NODES.arrayNode();
            arr.add(seedValue);
            target.set(memberName, arr);
        } else {
            target.set(memberName, NODES.textNode(seedValue));
        }
    }
}
