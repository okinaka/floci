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

    private static final String SSE_C_ALGORITHM = "SSECustomerAlgorithm";
    private static final String SSE_C_KEY = "SSECustomerKey";
    private static final String SSE_C_KEY_MD5 = "SSECustomerKeyMD5";

    private static final List<String> SSE_C_SET =
            List.of(SSE_C_ALGORITHM, SSE_C_KEY, SSE_C_KEY_MD5);
    private static final List<String> COPY_SOURCE_SSE_C_SET = List.of(
            "CopySourceSSECustomerAlgorithm", "CopySourceSSECustomerKey",
            "CopySourceSSECustomerKeyMD5");

    /**
     * Structure local name → groups of mutually-exclusive branches. Each branch
     * is a list of member names that travel together; when members of more than
     * one branch are present, the first branch with any member present is kept
     * and every member of the later branches is removed.
     *
     * <p>Only measured net-gain groups are listed. Pruning the
     * {@code EmailContent}/{@code Template} one-of was measured to net-lose PASS:
     * it fixed the positive Send* cases but stripped a one-of rejection that was
     * coincidentally satisfying the CLIENT_ERROR expectation of many negative
     * Send* probes (boundary/identifier-fanout on other members), turning those
     * back into inconclusive. The event-destination ops have no such negatives,
     * so pruning them is a clean gain.
     */
    private static final Map<String, List<List<List<String>>>> GROUPS = Map.of(
            // SES v2 event destination.
            "EventDestinationDefinition", List.of(List.of(
                    List.of("KinesisFirehoseDestination"), List.of("CloudWatchDestination"),
                    List.of("SnsDestination"), List.of("EventBridgeDestination"),
                    List.of("PinpointDestination"))),
            // SES v1 event destination (distinct shape name, note SNSDestination casing).
            // SNS is listed first because the pruner keeps the first present branch and
            // an SNS destination is valid with just a TopicARN, whereas a synthesized
            // CloudWatch destination carries null dimension fields that get rejected.
            "EventDestination", List.of(List.of(
                    List.of("SNSDestination"), List.of("CloudWatchDestination"),
                    List.of("KinesisFirehoseDestination"))),
            // S3 write ops: SSE-S3/KMS and SSE-C are mutually exclusive encryption
            // families ("SSE-C cannot be combined with x-amz-server-side-encryption").
            // ServerSideEncryption is kept so enum-exhaust over it stays meaningful;
            // the SSE-C write path is still exercised by shapes without a
            // ServerSideEncryption member (UploadPart, GetObject, ...).
            "PutObjectRequest", List.of(List.of(
                    List.of("ServerSideEncryption"), SSE_C_SET)),
            "CreateMultipartUploadRequest", List.of(List.of(
                    List.of("ServerSideEncryption"), SSE_C_SET)),
            "CopyObjectRequest", List.of(List.of(
                    List.of("ServerSideEncryption"), SSE_C_SET)));

    /**
     * Structure local name → member sets that are only valid complete ("SSE-C
     * requests require algorithm, key, and key MD5 headers"). When some but not
     * all members of a set are present — e.g. a property-based subset, or a
     * branch group above never fired — the partial set is removed entirely.
     */
    private static final Map<String, List<List<String>>> ALL_OR_NONE = Map.ofEntries(
            Map.entry("PutObjectRequest", List.of(SSE_C_SET)),
            Map.entry("CreateMultipartUploadRequest", List.of(SSE_C_SET)),
            Map.entry("CompleteMultipartUploadRequest", List.of(SSE_C_SET)),
            Map.entry("UploadPartRequest", List.of(SSE_C_SET)),
            Map.entry("GetObjectRequest", List.of(SSE_C_SET)),
            Map.entry("HeadObjectRequest", List.of(SSE_C_SET)),
            Map.entry("GetObjectAttributesRequest", List.of(SSE_C_SET)),
            Map.entry("ListPartsRequest", List.of(SSE_C_SET)),
            Map.entry("SelectObjectContentRequest", List.of(SSE_C_SET)),
            Map.entry("CopyObjectRequest", List.of(SSE_C_SET, COPY_SOURCE_SSE_C_SET)),
            Map.entry("UploadPartCopyRequest", List.of(SSE_C_SET, COPY_SOURCE_SSE_C_SET)),
            // Models only two of the three SSE-C members, so the set can never be
            // complete and is always stripped.
            Map.entry("WriteGetObjectResponseRequest", List.of(SSE_C_SET)));

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
        String structName = struct.getId().getName();
        for (List<List<String>> group : GROUPS.getOrDefault(structName, List.of())) {
            boolean kept = false;
            for (List<String> branch : group) {
                boolean present = branch.stream()
                        .anyMatch(m -> obj.get(m) != null && !obj.get(m).isNull());
                if (!present) {
                    continue;
                }
                if (kept) {
                    branch.forEach(obj::remove);
                } else {
                    kept = true;
                }
            }
        }
        for (List<String> set : ALL_OR_NONE.getOrDefault(structName, List.of())) {
            long present = set.stream()
                    .filter(m -> obj.get(m) != null && !obj.get(m).isNull())
                    .count();
            if (present > 0 && present < set.size()) {
                set.forEach(obj::remove);
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
