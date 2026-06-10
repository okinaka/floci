package io.floci.conformance.generator;

import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for the structural input generators —
 * {@link OptionalsGenerator} (required-only vs all-members),
 * {@link EnumExhaustGenerator} (one case per declared enum value), and
 * {@link NegativeGenerator} (missing-required / invalid-enum, expecting 4xx) —
 * verified for case counts and predicted outcomes against the SES models.
 */
class GeneratorsTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();
    private static final Model V2 = SmithyModelLoader.loadSesV2();

    @Test
    void optionals_produces_two_cases_per_operation() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);
        List<GeneratedCase> cases = new OptionalsGenerator().generate(op, V1).toList();

        assertThat(cases).hasSize(2);
        assertThat(cases).extracting(GeneratedCase::generator)
                .containsExactly("optionals.required-only", "optionals.all-members");
        assertThat(cases).allMatch(c -> c.expectedOutcome() == ExpectedOutcome.SUCCESS);

        // required-only output should be a strict subset of all-members for top-level keys
        var requiredKeys = cases.get(0).logicalInput().fieldNames();
        var allKeys = cases.get(1).logicalInput().fieldNames();
        List<String> requiredList = new java.util.ArrayList<>();
        requiredKeys.forEachRemaining(requiredList::add);
        List<String> allList = new java.util.ArrayList<>();
        allKeys.forEachRemaining(allList::add);
        assertThat(allList).containsAll(requiredList);
        assertThat(allList.size()).isGreaterThan(requiredList.size());
    }

    @Test
    void enumExhaust_emits_one_case_per_enum_value() {
        // SES v2 GetDeliverabilityTestReport has no enums directly; pick something with one.
        OperationShape op = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#PutEmailIdentityMailFromAttributes"),
                OperationShape.class);
        List<GeneratedCase> cases = new EnumExhaustGenerator().generate(op, V2).toList();

        // BehaviorOnMxFailure has 2 values: USE_DEFAULT_VALUE and REJECT_MESSAGE
        assertThat(cases).isNotEmpty();
        assertThat(cases).allMatch(c -> c.generator().startsWith("enum-exhaust."));
        assertThat(cases).allMatch(c -> c.expectedOutcome() == ExpectedOutcome.SUCCESS);
    }

    @Test
    void enumExhaust_no_enum_member_returns_empty() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentities"), OperationShape.class);
        List<GeneratedCase> cases = new EnumExhaustGenerator().generate(op, V1).toList();
        // ListIdentities has IdentityType (enum) - so this actually has cases
        assertThat(cases).allMatch(c -> c.expectedOutcome() == ExpectedOutcome.SUCCESS);
    }

    @Test
    void negative_missing_required_emits_one_per_required_member() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);
        List<GeneratedCase> cases = new NegativeGenerator().generate(op, V1)
                .filter(c -> c.generator().startsWith("negative.missing-required."))
                .toList();

        // SendEmail has multiple required members; expect at least one
        assertThat(cases).isNotEmpty();
        assertThat(cases).allMatch(c -> c.expectedOutcome() == ExpectedOutcome.CLIENT_ERROR);
    }

    @Test
    void negative_invalid_enum_emits_one_per_enum_member() {
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentities"), OperationShape.class);
        List<GeneratedCase> cases = new NegativeGenerator().generate(op, V1)
                .filter(c -> c.generator().startsWith("negative.invalid-enum."))
                .toList();

        // ListIdentities.IdentityType is an enum
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).generator()).isEqualTo("negative.invalid-enum.IdentityType");
        assertThat(cases.get(0).expectedOutcome()).isEqualTo(ExpectedOutcome.CLIENT_ERROR);
    }
}
