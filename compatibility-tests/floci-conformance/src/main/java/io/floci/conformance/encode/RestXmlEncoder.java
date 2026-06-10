package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.Variant;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.XmlNameTrait;
import software.amazon.smithy.model.traits.XmlNamespaceTrait;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes the logical input for the AWS REST XML protocol (S3, Route 53,
 * CloudFront): members split across path / query / headers by HTTP binding
 * trait, and the body comes from the {@code @httpPayload} member —
 * blob/string payloads as raw bytes, structure payloads serialized as XML
 * with the {@code @xmlName} root element and {@code @xmlNamespace}.
 *
 * <p>Operations without {@code @httpPayload} serialize any unbound members
 * as an XML document rooted at the input structure's {@code @xmlName}
 * (falling back to the shape name). Most S3 write ops use an explicit
 * payload member, so that path dominates.
 */
public final class RestXmlEncoder implements RequestEncoder {

    private static final String PROTOCOL = "aws.protocols#restXml";

    private final Model model;

    public RestXmlEncoder(Model model) {
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
        String rawBody = null;
        String contentType = null;

        JsonNode logical = g.logicalInput();
        if (logical != null && logical.isObject()
                && !op.getInputShape().toString().equals("smithy.api#Unit")) {
            Shape inputShape = model.expectShape(op.getInputShape());
            if (inputShape instanceof StructureShape struct) {
                StringBuilder unboundXml = new StringBuilder();
                for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
                    JsonNode v = logical.get(e.getKey());
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
                    } else if (m.hasTrait(HttpPayloadTrait.class)) {
                        Shape target = model.expectShape(m.getTarget());
                        if (target.getType() == ShapeType.BLOB) {
                            rawBody = decodeBase64(v.asText());
                            contentType = "application/octet-stream";
                        } else if (target.getType() == ShapeType.STRING) {
                            rawBody = v.asText();
                            contentType = "text/plain";
                        } else if (target instanceof StructureShape payloadStruct) {
                            rawBody = toXml(v, payloadStruct, m);
                            contentType = "application/xml";
                        }
                    } else {
                        appendXmlMember(unboundXml, v, m);
                    }
                }
                if (rawBody == null && unboundXml.length() > 0) {
                    String root = struct.getTrait(XmlNameTrait.class)
                            .map(XmlNameTrait::getValue)
                            .orElse(struct.getId().getName());
                    rawBody = "<" + root + ">" + unboundXml + "</" + root + ">";
                    contentType = "application/xml";
                }
            }
        }
        return new Variant(op, g.generator(), path, query, headers,
                null, rawBody, contentType,
                g.expectedOutcome(), g.expectedError());
    }

    /** Serialize a structure payload as an XML document. */
    private String toXml(JsonNode value, StructureShape struct, MemberShape payloadMember) {
        String root = payloadMember.getTrait(XmlNameTrait.class)
                .map(XmlNameTrait::getValue)
                .orElse(struct.getTrait(XmlNameTrait.class)
                        .map(XmlNameTrait::getValue)
                        .orElse(struct.getId().getName()));
        String xmlns = struct.getTrait(XmlNamespaceTrait.class)
                .map(XmlNamespaceTrait::getUri)
                .map(uri -> " xmlns=\"" + uri + "\"")
                .orElse("");
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(root).append(xmlns).append('>');
        writeStructMembers(sb, value, struct);
        sb.append("</").append(root).append('>');
        return sb.toString();
    }

    private void writeStructMembers(StringBuilder sb, JsonNode value, StructureShape struct) {
        for (Map.Entry<String, MemberShape> e : struct.getAllMembers().entrySet()) {
            JsonNode v = value.get(e.getKey());
            if (v == null || v.isNull() || v.isMissingNode()) {
                continue;
            }
            appendXmlMember(sb, v, e.getValue());
        }
    }

    private void appendXmlMember(StringBuilder sb, JsonNode value, MemberShape member) {
        String name = member.getTrait(XmlNameTrait.class)
                .map(XmlNameTrait::getValue)
                .orElse(member.getMemberName());
        Shape target = model.expectShape(member.getTarget());
        switch (target.getType()) {
            case STRUCTURE, UNION -> {
                sb.append('<').append(name).append('>');
                if (target instanceof StructureShape s) {
                    writeStructMembers(sb, value, s);
                }
                sb.append("</").append(name).append('>');
            }
            case LIST, SET -> {
                ListShape list = (ListShape) target;
                MemberShape element = list.getMember();
                String elementName = element.getTrait(XmlNameTrait.class)
                        .map(XmlNameTrait::getValue)
                        .orElse("member");
                sb.append('<').append(name).append('>');
                if (value.isArray()) {
                    for (JsonNode item : value) {
                        Shape elemTarget = model.expectShape(element.getTarget());
                        sb.append('<').append(elementName).append('>');
                        if (elemTarget instanceof StructureShape es) {
                            writeStructMembers(sb, item, es);
                        } else {
                            sb.append(escape(scalarToString(item)));
                        }
                        sb.append("</").append(elementName).append('>');
                    }
                }
                sb.append("</").append(name).append('>');
            }
            default -> sb.append('<').append(name).append('>')
                    .append(escape(scalarToString(value)))
                    .append("</").append(name).append('>');
        }
    }

    private static String decodeBase64(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return b64;
        }
    }

    private static String scalarToString(JsonNode v) {
        if (v.isBoolean()) {
            return v.booleanValue() ? "true" : "false";
        }
        if (v.isArray()) {
            return v.isEmpty() ? "" : scalarToString(v.get(0));
        }
        return v.asText();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
