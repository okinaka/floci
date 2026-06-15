package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes the logical input as a REST JSON request: splits the input
 * structure's members by HTTP binding trait — {@code @httpLabel} goes to path
 * params, {@code @httpQuery} to query string, {@code @httpHeader} to headers,
 * and everything else stays in the JSON body.
 */
public final class RestJsonEncoder implements RequestEncoder {

    private static final String PROTOCOL = "aws.protocols#restJson1";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Model model;

    public RestJsonEncoder(Model model) {
        this.model = model;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Variant encode(GeneratedCase g) {
        OperationShape op = g.operation();
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        ObjectNode body = NODES.objectNode();

        JsonNode logical = g.logicalInput();
        if (logical != null && logical.isObject() && !op.getInputShape().toString().equals("smithy.api#Unit")) {
            Shape inputShape = model.expectShape(op.getInputShape());
            if (inputShape instanceof StructureShape struct) {
                splitMembers(logical, struct, path, query, headers, body);
            }
        }
        return new Variant(op, g.generator(), path, query, headers,
                body.isEmpty() ? null : body,
                g.expectedOutcome(), g.expectedError());
    }

    private void splitMembers(JsonNode input, StructureShape struct,
                              Map<String, String> path,
                              Map<String, String> query,
                              Map<String, String> headers,
                              ObjectNode body) {
        for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
            JsonNode v = input.get(e.getKey());
            if (v == null || v.isNull() || v.isMissingNode()) {
                continue;
            }
            MemberShape m = e.getValue();
            if (m.hasTrait(HttpLabelTrait.class)) {
                path.put(e.getKey(), scalarToString(v));
            } else if (m.hasTrait(HttpQueryTrait.class)) {
                query.put(m.getTrait(HttpQueryTrait.class).get().getValue(), scalarToString(v));
            } else if (m.hasTrait(HttpHeaderTrait.class)) {
                headers.put(m.getTrait(HttpHeaderTrait.class).get().getValue(), scalarToString(v));
            } else {
                body.set(e.getKey(), v);
            }
        }
    }

    private static String scalarToString(JsonNode v) {
        if (v.isBoolean()) {
            return v.booleanValue() ? "true" : "false";
        }
        // Containers don't stringify via asText() (it returns ""). A
        // list-valued @httpQuery should serialize as a repeated param, which
        // Variant's Map<String,String> can't carry — take the first element so
        // single-element synthesized lists round-trip correctly instead of
        // producing an empty value.
        if (v.isArray()) {
            return v.isEmpty() ? "" : scalarToString(v.get(0));
        }
        return v.asText();
    }
}
