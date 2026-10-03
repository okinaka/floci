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
 * (short name / ARN / wrong-region / wrong-account) per ARN-capable
 * identifier member, two for a name-only one, the member-name heuristic, and
 * skipping of ops with no identifier.
 */
class IdentifierFanoutGeneratorTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();
    private static final Model DDB = SmithyModelLoader.loadDynamoDb();

    @Test
    void emits_four_variants_per_arn_capable_member() {
        // GetIdentityPolicies' Identity takes "its name or ... its Amazon
        // Resource Name (ARN)", and PolicyNames is a list, so Identity is the
        // only fanned-out member.
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityPolicies"), OperationShape.class);
        List<GeneratedCase> cases = new IdentifierFanoutGenerator().generate(op, V1).toList();

        assertThat(cases).extracting(GeneratedCase::generator).containsExactlyInAnyOrder(
                "identifier-fanout.short.Identity",
                "identifier-fanout.arn.Identity",
                "identifier-fanout.wrong-region.Identity",
                "identifier-fanout.wrong-account.Identity");

        // GetIdentityPolicies declares no not-found error (AWS answers an
        // unknown identity with an empty policy map), so even the wrong-*
        // variants expect SUCCESS.
        for (GeneratedCase c : cases) {
            assertThat(c.expectedOutcome()).isEqualTo(ExpectedOutcome.SUCCESS);
        }
    }

    @Test
    void name_only_member_gets_no_wrong_variants() {
        // GetCustomVerificationEmailTemplate's TemplateName is a name only. An
        // ARN-shaped value is just another name, which the same-label create
        // case creates, so a wrong-account/wrong-region read would rightly be
        // a 200: the variants test nothing and are not emitted.
        OperationShape op = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetCustomVerificationEmailTemplate"), OperationShape.class);
        List<GeneratedCase> cases = new IdentifierFanoutGenerator().generate(op, V1).toList();

        assertThat(cases).extracting(GeneratedCase::generator).containsExactlyInAnyOrder(
                "identifier-fanout.short.TemplateName",
                "identifier-fanout.arn.TemplateName");
    }

    @Test
    void wrong_variants_expect_error_only_for_strict_lookup_ops() {
        // DescribeTable declares ResourceNotFoundException and its TableName
        // accepts a table ARN, so an ARN in another account or region must
        // be rejected: wrong-* predicts CLIENT_ERROR.
        OperationShape op = DDB.expectShape(
                ShapeId.from("com.amazonaws.dynamodb#DescribeTable"), OperationShape.class);
        List<GeneratedCase> cases = new IdentifierFanoutGenerator().generate(op, DDB).toList();

        assertThat(cases).extracting(GeneratedCase::generator)
                .contains("identifier-fanout.wrong-account.TableName");
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
