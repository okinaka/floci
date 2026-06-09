package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Probes identifier-form handling: for each input string member whose name
 * looks like an identifier ({@code *Name}, {@code *Arn}, {@code *Id},
 * {@code *Identity}), emit variants with three forms of the same logical
 * resource:
 *
 * <ul>
 *   <li>{@code identifier-fanout.short.<member>} — bare name; predicts SUCCESS.
 *   <li>{@code identifier-fanout.arn.<member>} — full AWS ARN with the same
 *       bare name as the tail; predicts SUCCESS.
 *   <li>{@code identifier-fanout.wrong-region.<member>} — ARN with a region
 *       that doesn't match the configured one; predicts CLIENT_ERROR.
 *   <li>{@code identifier-fanout.wrong-account.<member>} — ARN with the
 *       all-zeros account; predicts CLIENT_ERROR.
 * </ul>
 *
 * <p>The other members of the input are filled with a baseline synthesizer
 * so only the identifier varies.
 */
public final class IdentifierFanoutGenerator implements Generator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final String BARE_NAME = "cov-probe-resource";
    private static final String SERVICE_HINT_DEFAULT = "ses";

    @Override
    public String name() {
        return "identifier-fanout";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        String serviceHint = inferServiceHint(op);
        List<GeneratedCase> cases = new ArrayList<>();
        for (MemberShape member : struct.getAllMembers().values()) {
            Shape target = model.expectShape(member.getTarget());
            if (target.getType() != ShapeType.STRING) {
                continue;
            }
            if (!looksLikeIdentifier(member.getMemberName())) {
                continue;
            }
            String resourceType = resourceTypeFor(member.getMemberName());
            String bareArn = "arn:aws:" + serviceHint + ":us-east-1:123456789012:"
                    + resourceType + "/" + BARE_NAME;
            String wrongRegionArn = "arn:aws:" + serviceHint + ":eu-west-99:123456789012:"
                    + resourceType + "/" + BARE_NAME;
            String wrongAccountArn = "arn:aws:" + serviceHint + ":us-east-1:000000000000:"
                    + resourceType + "/" + BARE_NAME;

            emit(op, struct, model, member, BARE_NAME,
                    "identifier-fanout.short." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, null, cases);
            emit(op, struct, model, member, bareArn,
                    "identifier-fanout.arn." + member.getMemberName(),
                    ExpectedOutcome.SUCCESS, null, cases);
            emit(op, struct, model, member, wrongRegionArn,
                    "identifier-fanout.wrong-region." + member.getMemberName(),
                    ExpectedOutcome.CLIENT_ERROR, null, cases);
            emit(op, struct, model, member, wrongAccountArn,
                    "identifier-fanout.wrong-account." + member.getMemberName(),
                    ExpectedOutcome.CLIENT_ERROR, null, cases);
        }
        return cases.stream();
    }

    static boolean looksLikeIdentifier(String memberName) {
        String n = memberName;
        return n.endsWith("Arn") || n.endsWith("ARN")
                || n.endsWith("Id") || n.endsWith("ID")
                || n.endsWith("Name") || n.endsWith("Identity")
                || n.equals("Identities");
    }

    /** Guess the ARN resource-type segment from the member name. */
    private static String resourceTypeFor(String memberName) {
        String lower = memberName.toLowerCase();
        if (lower.contains("template")) {
            return "template";
        }
        if (lower.contains("ruleset")) {
            return "receipt-rule-set";
        }
        if (lower.contains("rule")) {
            return "receipt-rule";
        }
        if (lower.contains("configuration") || lower.contains("configset")) {
            return "configuration-set";
        }
        return "identity";
    }

    /** Service segment for synthetic ARNs. Override via op trait inspection later if needed. */
    private static String inferServiceHint(OperationShape op) {
        String namespace = op.getId().getNamespace();
        if (namespace.contains("sesv2") || namespace.contains("ses")) {
            return SERVICE_HINT_DEFAULT;
        }
        // Pull last dotted segment; usually maps to the service identifier.
        int dot = namespace.lastIndexOf('.');
        return dot >= 0 ? namespace.substring(dot + 1) : SERVICE_HINT_DEFAULT;
    }

    private static void emit(OperationShape op, StructureShape struct, Model model,
                             MemberShape member, String value,
                             String generatorName, ExpectedOutcome outcome, String expectedError,
                             List<GeneratedCase> out) {
        InputSynthesizer base = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode input = base.synthesizeInput(struct);
        input.set(member.getMemberName(), NODES.textNode(value));
        out.add(new GeneratedCase(op, generatorName, input, outcome, expectedError));
    }
}
