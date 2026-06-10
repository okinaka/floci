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
 * Offline unit tests for {@link IdentifierFanoutGenerator}: four variants
 * (short name / ARN / wrong-region / wrong-account) per identifier-shaped
 * member, the member-name heuristic, and skipping of ops with no identifier.
 */
class IdentifierFanoutGeneratorTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();

    @Test
    void emits_four_variants_per_identifier_member() {
        // DeleteIdentity has Identity (string, identifier-shaped name).
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#DeleteIdentity"), OperationShape.class);
        List<GeneratedCase> cases = new IdentifierFanoutGenerator().generate(op, V1).toList();

        // Identity is the only id-like member; 4 variants.
        assertThat(cases).hasSize(4);
        var generators = cases.stream().map(GeneratedCase::generator).toList();
        assertThat(generators).contains(
                "identifier-fanout.short.Identity",
                "identifier-fanout.arn.Identity",
                "identifier-fanout.wrong-region.Identity",
                "identifier-fanout.wrong-account.Identity");

        // SUCCESS for short/arn; CLIENT_ERROR for wrong-*.
        for (GeneratedCase c : cases) {
            if (c.generator().contains("wrong-")) {
                assertThat(c.expectedOutcome()).isEqualTo(ExpectedOutcome.CLIENT_ERROR);
            } else {
                assertThat(c.expectedOutcome()).isEqualTo(ExpectedOutcome.SUCCESS);
            }
        }
    }

    @Test
    void skips_non_identifier_members() {
        // GetSendQuota has no input fields → no cases.
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetSendQuota"), OperationShape.class);
        assertThat(new IdentifierFanoutGenerator().generate(op, V1).toList()).isEmpty();
    }

    @Test
    void heuristic_matches_expected_suffixes() {
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("RoleArn")).isTrue();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("Identity")).isTrue();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("UserId")).isTrue();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("PolicyName")).isTrue();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("Identities")).isTrue();
        // Non-identifier-shaped:
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("Body")).isFalse();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("MaxItems")).isFalse();
        assertThat(IdentifierFanoutGenerator.looksLikeIdentifier("Enabled")).isFalse();
    }
}
