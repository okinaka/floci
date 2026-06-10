package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.RequiredTrait;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Produces negative cases that should provoke a 4xx with an AWS-shaped error.
 *
 * <ul>
 *   <li>{@code negative.missing-required.<member>} — one variant per
 *       {@code @required} member, omitting just that field. Catches handlers
 *       that fail-open on required-field validation.
 *   <li>{@code negative.invalid-enum.<member>} — one variant per enum-typed
 *       member, substituting a value the enum doesn't declare. Catches
 *       handlers that don't validate enum membership.
 * </ul>
 *
 * <p>{@code @httpLabel} required members are skipped from "missing-required"
 * since their absence yields a routing 404, not an application-level error,
 * which would muddy the verdict.
 */
public final class NegativeGenerator implements Generator {

    private static final String INVALID_ENUM_LITERAL = "cov-not-a-real-enum-value";

    @Override
    public String name() {
        return "negative";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        List<GeneratedCase> cases = new ArrayList<>();
        cases.addAll(missingRequiredCases(op, struct, model));
        cases.addAll(invalidEnumCases(op, struct, model));
        return cases.stream();
    }

    private List<GeneratedCase> missingRequiredCases(OperationShape op, StructureShape struct, Model model) {
        List<GeneratedCase> cases = new ArrayList<>();
        for (MemberShape required : struct.getAllMembers().values()) {
            if (!required.hasTrait(RequiredTrait.class)) {
                continue;
            }
            if (required.hasTrait(HttpLabelTrait.class)) {
                continue;
            }
            final String omitted = required.getMemberName();
            Predicate<MemberShape> excludeOne = m -> !m.getMemberName().equals(omitted);
            InputSynthesizer synth = new InputSynthesizer(model, excludeOne, null);
            ObjectNode input = synth.synthesizeInput(struct);
            cases.add(new GeneratedCase(
                    op,
                    "negative.missing-required." + omitted,
                    input,
                    ExpectedOutcome.CLIENT_ERROR,
                    null));
        }
        return cases;
    }

    private List<GeneratedCase> invalidEnumCases(OperationShape op, StructureShape struct, Model model) {
        List<GeneratedCase> cases = new ArrayList<>();
        ObjectNode baseline = null;
        for (MemberShape member : struct.getAllMembers().values()) {
            Shape target = model.expectShape(member.getTarget());
            if (!(target instanceof EnumShape)) {
                continue;
            }
            if (baseline == null) {
                baseline = new InputSynthesizer(
                        model, InputSynthesizer.allMembers(), null).synthesizeInput(struct);
            }
            ObjectNode input = baseline.deepCopy();
            input.set(member.getMemberName(), JsonNodeFactory.instance.textNode(INVALID_ENUM_LITERAL));
            cases.add(new GeneratedCase(
                    op,
                    "negative.invalid-enum." + member.getMemberName(),
                    input,
                    ExpectedOutcome.CLIENT_ERROR,
                    null));
        }
        return cases;
    }
}
