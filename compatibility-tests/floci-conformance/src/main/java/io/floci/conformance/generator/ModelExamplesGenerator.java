package io.floci.conformance.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.conformance.model.ExpectedOutcome;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.traits.ExamplesTrait;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Replays {@code smithy.api#examples} from the model verbatim.
 *
 * <p>AWS authors these as canonical illustrations of valid input + expected
 * output (or error) — they're the highest-signal positive cases available.
 *
 * <p>Each example becomes one case:
 * <ul>
 *   <li>has {@code output} → predicts {@link ExpectedOutcome#SUCCESS}.
 *   <li>has {@code error} → predicts {@link ExpectedOutcome#CLIENT_ERROR}
 *       with the named error shape.
 *   <li>has neither → SUCCESS (the example only documents valid input).
 * </ul>
 */
public final class ModelExamplesGenerator implements Generator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "model-examples";
    }

    @Override
    public Stream<GeneratedCase> generate(OperationShape op, Model model) {
        ExamplesTrait trait = op.getTrait(ExamplesTrait.class).orElse(null);
        if (trait == null) {
            return Stream.empty();
        }
        List<GeneratedCase> cases = new ArrayList<>();
        int idx = 0;
        for (ExamplesTrait.Example example : trait.getExamples()) {
            idx++;
            JsonNode input = smithyToJackson(example.getInput());
            ExpectedOutcome outcome = ExpectedOutcome.SUCCESS;
            String expectedError = null;
            if (example.getError().isPresent()) {
                outcome = ExpectedOutcome.CLIENT_ERROR;
                expectedError = example.getError().get().getShapeId().getName();
            }
            String label = "model-examples." + idx + "." + slug(example.getTitle());
            cases.add(new GeneratedCase(op, label, input, outcome, expectedError));
        }
        return cases.stream();
    }

    private static JsonNode smithyToJackson(Node node) {
        try {
            return MAPPER.readTree(Node.printJson(node));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to convert Smithy node to Jackson", e);
        }
    }

    private static String slug(String s) {
        if (s == null || s.isBlank()) {
            return "untitled";
        }
        return s.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
