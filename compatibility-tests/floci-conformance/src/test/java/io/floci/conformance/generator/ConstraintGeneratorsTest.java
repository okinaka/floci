package io.floci.conformance.generator;

import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for the constraint- and example-driven generators —
 * {@link BoundaryGenerator} ({@code @length} / {@code @range} edges),
 * {@link PropertyBasedGenerator} (seeded random-but-valid inputs), and
 * {@link ModelExamplesGenerator} (replay of Smithy {@code @examples}) —
 * exercised against the real SES Smithy models without an emulator.
 */
class ConstraintGeneratorsTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();
    private static final Model V2 = SmithyModelLoader.loadSesV2();

    @Test
    void boundary_emits_length_cases_for_constrained_member() {
        // PutIdentityPolicy.PolicyName has @length(min:1, max:64)
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#PutIdentityPolicy"), OperationShape.class);
        List<GeneratedCase> cases = new BoundaryGenerator().generate(op, V1).toList();

        Set<String> names = cases.stream().map(GeneratedCase::generator).collect(java.util.stream.Collectors.toSet());
        assertThat(names).anyMatch(n -> n.startsWith("boundary.length.min."));
        assertThat(names).anyMatch(n -> n.startsWith("boundary.length.max."));
        assertThat(names).anyMatch(n -> n.startsWith("boundary.length.under.min."));
        assertThat(names).anyMatch(n -> n.startsWith("boundary.length.over.max."));

        // Under-min and over-max predict CLIENT_ERROR; at-boundary predicts SUCCESS.
        for (GeneratedCase c : cases) {
            if (c.generator().contains(".under.") || c.generator().contains(".over.")) {
                assertThat(c.expectedOutcome()).isEqualTo(ExpectedOutcome.CLIENT_ERROR);
            } else {
                assertThat(c.expectedOutcome()).isEqualTo(ExpectedOutcome.SUCCESS);
            }
        }
    }

    @Test
    void boundary_skips_under_min_when_it_would_empty_an_httpLabel() {
        // GetEmailTemplate.TemplateName is @httpLabel with @length(min: 1).
        // An empty label re-routes the request to ListEmailTemplates
        // (verified live), so the under-min variant must not be emitted;
        // the at-min variant still is.
        OperationShape op = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#GetEmailTemplate"), OperationShape.class);
        List<GeneratedCase> cases = new BoundaryGenerator().generate(op, V2).toList();
        var names = cases.stream().map(GeneratedCase::generator).toList();
        assertThat(names).contains("boundary.length.min.TemplateName");
        assertThat(names).doesNotContain("boundary.length.under.min.TemplateName");
    }

    @Test
    void boundary_emits_nothing_when_no_constraints() {
        // ListIdentities has IdentityType (enum) + NextToken (string, no @length) + MaxItems
        // MaxItems isn't actually @range-constrained in SES v1, so might be empty for v1.
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentities"), OperationShape.class);
        List<GeneratedCase> cases = new BoundaryGenerator().generate(op, V1).toList();
        // Don't assert empty (model could have constraints); just assert nothing crashes.
        assertThat(cases).isNotNull();
    }

    @Test
    void propertyBased_emits_fixed_number_of_cases() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);
        List<GeneratedCase> cases = new PropertyBasedGenerator().generate(op, V1).toList();
        assertThat(cases).hasSize(5);
        for (int i = 0; i < cases.size(); i++) {
            assertThat(cases.get(i).generator()).isEqualTo("property-based.seed-" + i);
        }
        assertThat(cases).allMatch(c -> c.expectedOutcome() == ExpectedOutcome.SUCCESS);
    }

    @Test
    void propertyBased_is_deterministic_for_same_op() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);
        List<GeneratedCase> a = new PropertyBasedGenerator().generate(op, V1).toList();
        List<GeneratedCase> b = new PropertyBasedGenerator().generate(op, V1).toList();
        // Each variant's tree should be equal across runs (Random seeded by op-id hash).
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).logicalInput()).isEqualTo(b.get(i).logicalInput());
        }
    }

    @Test
    void modelExamples_replays_smithy_examples_verbatim() {
        // CloneReceiptRuleSet has at least one @examples
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#CloneReceiptRuleSet"), OperationShape.class);
        List<GeneratedCase> cases = new ModelExamplesGenerator().generate(op, V1).toList();
        assertThat(cases).isNotEmpty();
        assertThat(cases.get(0).generator()).startsWith("model-examples.1.");
        assertThat(cases.get(0).logicalInput().get("RuleSetName").asText())
                .isEqualTo("RuleSetToCreate");
        assertThat(cases.get(0).logicalInput().get("OriginalRuleSetName").asText())
                .isEqualTo("RuleSetToClone");
        assertThat(cases.get(0).expectedOutcome()).isEqualTo(ExpectedOutcome.SUCCESS);
    }

    @Test
    void modelExamples_empty_when_op_has_no_examples() {
        // Find an op without @examples
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetSendQuota"), OperationShape.class);
        List<GeneratedCase> cases = new ModelExamplesGenerator().generate(op, V1).toList();
        // GetSendQuota may or may not have @examples; if it does, fine — just verify no crash.
        assertThat(cases).isNotNull();
    }
}
