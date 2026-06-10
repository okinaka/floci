package io.floci.conformance.classify;

import io.floci.conformance.classify.ErrorClassifier.Category;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link ErrorClassifier}: the not-implemented
 * allowlist and status rules (501 / 405), state-collision vs not-found vs
 * validation pattern buckets, Smithy declared-error lookup, and error-code
 * normalization ({@code Sender.X} / namespaced forms).
 */
class ErrorClassifierTest {

    private static final Model SES_V1 = SmithyModelLoader.loadSesV1();
    private static final OperationShape SEND_EMAIL = SES_V1.expectShape(
            ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void notImplemented_marker_types_resolve_to_NOT_IMPLEMENTED() {
        assertThat(classifier.classify(SEND_EMAIL, 400, "UnsupportedOperation"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
        assertThat(classifier.classify(SEND_EMAIL, 400, "NotImplementedException"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
    }

    @Test
    void status_501_and_405_resolve_to_NOT_IMPLEMENTED_regardless_of_type() {
        // LocalStack: 501 + InternalFailure; fakecloud: 501 + InvalidAction.
        assertThat(classifier.classify(SEND_EMAIL, 501, "InternalFailure"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
        // ministack: 405 + MethodNotAllowed.
        assertThat(classifier.classify(SEND_EMAIL, 405, "MethodNotAllowed"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
        // Same type names at 400/500 do NOT trip the status rule.
        assertThat(classifier.classify(SEND_EMAIL, 500, "InternalFailure"))
                .isNotEqualTo(Category.NOT_IMPLEMENTED);
    }

    @Test
    void state_patterns_resolve_to_STATE() {
        assertThat(classifier.classify(SEND_EMAIL, 400, "ConfigurationSetAlreadyExists"))
                .isEqualTo(Category.STATE);
        assertThat(classifier.classify(SEND_EMAIL, 400, "ResourceInUseException"))
                .isEqualTo(Category.STATE);
        assertThat(classifier.classify(SEND_EMAIL, 400, "LimitExceededException"))
                .isEqualTo(Category.STATE);
    }

    @Test
    void not_found_patterns_resolve_to_MISSING() {
        assertThat(classifier.classify(SEND_EMAIL, 400, "ResourceNotFoundException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, 400, "TemplateDoesNotExistException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, 400, "NotFoundException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, 400, "NoSuchEntity"))
                .isEqualTo(Category.MISSING);
    }

    @Test
    void validation_patterns_resolve_to_VALIDATION() {
        assertThat(classifier.classify(SEND_EMAIL, 400, "InvalidParameterValue"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, 400, "ValidationException"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, 400, "ValidationError"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, 400, "MalformedInput"))
                .isEqualTo(Category.VALIDATION);
    }

    @Test
    void smithy_declared_errors_resolve_to_DECLARED_BY_OP() {
        // SendEmail declares MessageRejected, MailFromDomainNotVerifiedException, etc.
        assertThat(classifier.classify(SEND_EMAIL, 400, "MessageRejected"))
                .isEqualTo(Category.DECLARED_BY_OP);
    }

    @Test
    void unknown_falls_through_to_OTHER() {
        assertThat(classifier.classify(SEND_EMAIL, 400, "WeirdMadeUpError"))
                .isEqualTo(Category.OTHER);
    }

    @Test
    void normalize_strips_namespace_and_dotted_prefix() {
        assertThat(ErrorClassifier.normalize("com.amazonaws.ses#MessageRejected"))
                .isEqualTo("MessageRejected");
        assertThat(ErrorClassifier.normalize("Sender.InvalidParameterValue"))
                .isEqualTo("InvalidParameterValue");
        assertThat(ErrorClassifier.normalize("MessageRejected")).isEqualTo("MessageRejected");
    }

    @Test
    void null_or_blank_is_OTHER() {
        assertThat(classifier.classify(SEND_EMAIL, 400, null)).isEqualTo(Category.OTHER);
        assertThat(classifier.classify(SEND_EMAIL, 400, "")).isEqualTo(Category.OTHER);
        assertThat(classifier.classify(SEND_EMAIL, 400, "  ")).isEqualTo(Category.OTHER);
    }
}
