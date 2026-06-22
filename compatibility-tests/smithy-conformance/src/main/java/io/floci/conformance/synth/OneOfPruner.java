package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.List;
import java.util.Map;

/**
 * Drops all-but-one member of a mutually-exclusive ("exactly one of") group from
 * a synthesized input. The {@code optionals.all-members} generator fills every
 * optional member, but AWS rejects inputs that set more than one branch of a
 * one-of group (e.g. {@code EmailContent} must carry exactly one of
 * {@code Simple} / {@code Raw} / {@code Template}). The model doesn't express
 * these constraints machine-readably, so they live here as a small declarative
 * table keyed by structure name.
 *
 * <p>The walk is model-parallel: each JSON object is matched to its Smithy
 * structure shape, so a group only prunes the structure it belongs to and can't
 * collide with a same-named member on an unrelated shape. Structures not in the
 * table — i.e. every shape outside the listed services — are left untouched.
 */
public final class OneOfPruner {

    /**
     * Structure local name → groups of members of which at most one may be set.
     *
     * <p>Only {@code EventDestinationDefinition} is pruned. Pruning the
     * {@code EmailContent}/{@code Template} one-of was measured to net-lose PASS:
     * it fixed the positive Send* cases but stripped a one-of rejection that was
     * coincidentally satisfying the CLIENT_ERROR expectation of many negative
     * Send* probes (boundary/identifier-fanout on other members), turning those
     * back into inconclusive. The event-destination ops have no such negatives,
     * so pruning them is a clean gain.
     */
    private static final Map<String, List<List<String>>> GROUPS = Map.of(
            "EventDestinationDefinition", List.of(List.of(
                    "KinesisFirehoseDestination", "CloudWatchDestination", "SnsDestination",
                    "EventBridgeDestination", "PinpointDestination")));

    private final Model model;

    public OneOfPruner(Model model) {
        this.model = model;
    }

    /** Returns {@code input} with one-of groups reduced to a single branch (mutates in place). */
    public JsonNode prune(JsonNode input, StructureShape inputShape) {
        if (input != null && input.isObject() && inputShape != null) {
            walkStruct(input, inputShape);
        }
        return input;
    }

    private void walkStruct(JsonNode node, StructureShape struct) {
        if (!node.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        for (List<String> group : GROUPS.getOrDefault(struct.getId().getName(), List.of())) {
            boolean kept = false;
            for (String member : group) {
                JsonNode v = obj.get(member);
                if (v == null || v.isNull()) {
                    continue;
                }
                if (kept) {
                    obj.remove(member);
                } else {
                    kept = true;
                }
            }
        }
        for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
            JsonNode child = obj.get(e.getKey());
            if (child != null && !child.isNull()) {
                walkValue(child, model.expectShape(e.getValue().getTarget()));
            }
        }
    }

    private void walkValue(JsonNode value, Shape shape) {
        switch (shape.getType()) {
            case STRUCTURE -> walkStruct(value, (StructureShape) shape);
            case LIST, SET -> {
                if (value.isArray()) {
                    Shape element = model.expectShape(((ListShape) shape).getMember().getTarget());
                    for (JsonNode item : value) {
                        walkValue(item, element);
                    }
                }
            }
            case MAP -> {
                if (value.isObject()) {
                    Shape valShape = model.expectShape(((MapShape) shape).getValue().getTarget());
                    value.forEach(v -> walkValue(v, valShape));
                }
            }
            default -> { /* scalars: nothing to prune */ }
        }
    }
}
