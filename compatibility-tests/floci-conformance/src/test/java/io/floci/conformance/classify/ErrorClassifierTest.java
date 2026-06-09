package io.floci.conformance.classify;

import io.floci.conformance.classify.ErrorClassifier.Category;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassifierTest {

    private static final Model SES_V1 = SmithyModelLoader.loadSesV1();
    private static final OperationShape SEND_EMAIL = SES_V1.expectShape(
            ShapeId.from("com.amazonaws.ses#SendEmail"), OperationShape.class);

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void notImplemented_marker_types_resolve_to_NOT_IMPLEMENTED() {
        assertThat(classifier.classify(SEND_EMAIL, "UnsupportedOperation"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
        assertThat(classifier.classify(SEND_EMAIL, "NotImplementedException"))
                .isEqualTo(Category.NOT_IMPLEMENTED);
    }

    @Test
    void state_patterns_resolve_to_STATE() {
        assertThat(classifier.classify(SEND_EMAIL, "ConfigurationSetAlreadyExists"))
                .isEqualTo(Category.STATE);
        assertThat(classifier.classify(SEND_EMAIL, "ResourceInUseException"))
                .isEqualTo(Category.STATE);
        assertThat(classifier.classify(SEND_EMAIL, "LimitExceededException"))
                .isEqualTo(Category.STATE);
    }

    @Test
    void not_found_patterns_resolve_to_MISSING() {
        assertThat(classifier.classify(SEND_EMAIL, "ResourceNotFoundException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, "TemplateDoesNotExistException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, "NotFoundException"))
                .isEqualTo(Category.MISSING);
        assertThat(classifier.classify(SEND_EMAIL, "NoSuchEntity"))
                .isEqualTo(Category.MISSING);
    }

    @Test
    void validation_patterns_resolve_to_VALIDATION() {
        assertThat(classifier.classify(SEND_EMAIL, "InvalidParameterValue"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, "ValidationException"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, "ValidationError"))
                .isEqualTo(Category.VALIDATION);
        assertThat(classifier.classify(SEND_EMAIL, "MalformedInput"))
                .isEqualTo(Category.VALIDATION);
    }

    @Test
    void smithy_declared_errors_resolve_to_DECLARED_BY_OP() {
        // SendEmail declares MessageRejected, MailFromDomainNotVerifiedException, etc.
        assertThat(classifier.classify(SEND_EMAIL, "MessageRejected"))
                .isEqualTo(Category.DECLARED_BY_OP);
    }

    @Test
    void unknown_falls_through_to_OTHER() {
        assertThat(classifier.classify(SEND_EMAIL, "WeirdMadeUpError"))
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
        assertThat(classifier.classify(SEND_EMAIL, null)).isEqualTo(Category.OTHER);
        assertThat(classifier.classify(SEND_EMAIL, "")).isEqualTo(Category.OTHER);
        assertThat(classifier.classify(SEND_EMAIL, "  ")).isEqualTo(Category.OTHER);
    }
}
