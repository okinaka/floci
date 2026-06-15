package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

import java.util.stream.Stream;

/**
 * Generates two cases per operation: one with only {@code @required} members
 * filled in, and one with every member filled in. Catches handlers that
 * silently mishandle the boundary between "required" and "optional" (the
 * classic Floci bug: optional fields ignored on update, dropped on round-trip,
 * etc.).
 *
 * <p>Both cases predict {@link ExpectedOutcome#SUCCESS}.
 */
public final class OptionalsGenerator implements Generator {

    @Override
    public String name() {
        return "optionals";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        Shape inputShape = model.expectShape(op.getInputShape());
        if (!(inputShape instanceof StructureShape struct)) {
            return Stream.empty();
        }
        InputSynthesizer requiredOnly = new InputSynthesizer(model, InputSynthesizer.requiredOnly(), null);
        InputSynthesizer allMembers = new InputSynthesizer(model, InputSynthesizer.allMembers(), null);
        ObjectNode requiredInput = requiredOnly.synthesizeInput(struct);
        ObjectNode allInput = allMembers.synthesizeInput(struct);

        return Stream.of(
                new GeneratedCase(op, "optionals.required-only", requiredInput,
                        ExpectedOutcome.SUCCESS, null),
                new GeneratedCase(op, "optionals.all-members", allInput,
                        ExpectedOutcome.SUCCESS, null));
    }
}
