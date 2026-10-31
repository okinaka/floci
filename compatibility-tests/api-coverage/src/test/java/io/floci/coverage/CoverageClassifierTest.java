package io.floci.coverage;

import io.floci.coverage.invoke.InvocationResponse;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests for {@link CoverageClassifier}: which responses prove an
 * operation is dispatched and which mean it is not.
 */
class CoverageClassifierTest {

    private static final String JSON = "application/x-amz-json-1.1";

    private static final Model MODEL = Model.assembler()
            .addUnparsedModel("test.smithy", """
                    $version: "2"
                    namespace test.svc

                    service Widgets {
                        version: "2020-01-01"
                        operations: [GetWidget, HeadWidget]
                    }

                    @http(method: "GET", uri: "/widgets/{Id}")
                    @readonly
                    operation GetWidget {
                        input := { @required @httpLabel Id: String }
                        errors: [WidgetNotFound]
                    }

                    @http(method: "HEAD", uri: "/widgets/{Id}")
                    @readonly
                    operation HeadWidget {
                        input := { @required @httpLabel Id: String }
                    }

                    @error("client")
                    structure WidgetNotFound {}
                    """)
            .assemble()
            .unwrap();

    private static final ServiceShape SERVICE = MODEL.expectShape(
            ShapeId.from("test.svc#Widgets"), ServiceShape.class);
    private static final OperationShape GET = MODEL.expectShape(
            ShapeId.from("test.svc#GetWidget"), OperationShape.class);
    private static final OperationShape HEAD = MODEL.expectShape(
            ShapeId.from("test.svc#HeadWidget"), OperationShape.class);

    private final CoverageClassifier classifier = new CoverageClassifier(MODEL, SERVICE);

    private static InvocationResponse json(int status, String type, String message) {
        return new InvocationResponse(status, JSON,
                "{\"__type\":\"" + type + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    void success_isImplemented() {
        assertThat(classifier.classify(GET, new InvocationResponse(200, JSON, "{}")))
                .isEqualTo(CoverageStatus.IMPLEMENTED);
    }

    @Test
    void anyAwsClientError_isImplemented() {
        assertThat(classifier.classify(GET, json(404, "WidgetNotFound", "no widget")))
                .isEqualTo(CoverageStatus.IMPLEMENTED);
        assertThat(classifier.classify(GET, json(400, "ValidationException", "Id too long")))
                .isEqualTo(CoverageStatus.IMPLEMENTED);
        assertThat(classifier.classify(GET, json(404, "ResourceNotFoundException", "gone")))
                .isEqualTo(CoverageStatus.IMPLEMENTED);
    }

    @Test
    void notImplementedSignals_areNotImplemented() {
        assertThat(classifier.classify(GET, json(400, "UnsupportedOperation", "x")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
        assertThat(classifier.classify(GET, json(400, "UnknownAction", "x")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
        assertThat(classifier.classify(GET, json(501, "InternalFailure", "x")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
        assertThat(classifier.classify(GET, json(405, "MethodNotAllowed", "x")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
    }

    @Test
    void messageNamingTheOperationAsUnsupported_isNotImplemented() {
        assertThat(classifier.classify(GET,
                json(400, "ValidationException", "Operation GetWidget is not supported by this emulator.")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
    }

    @Test
    void unsupportedMessageAboutSomethingElse_isImplemented() {
        assertThat(classifier.classify(GET,
                json(400, "ValidationException", "Parameter Color is not supported.")))
                .isEqualTo(CoverageStatus.IMPLEMENTED);
    }

    @Test
    void untyped404And406_areUnrouted() {
        assertThat(classifier.classify(GET, new InvocationResponse(404, "text/html", "<html>nope</html>")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
        assertThat(classifier.classify(GET, new InvocationResponse(406, null, "")))
                .isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
    }

    @Test
    void headWithoutBody_isAmbiguous() {
        assertThat(classifier.classify(HEAD, new InvocationResponse(404, null, "")))
                .isEqualTo(CoverageStatus.AMBIGUOUS);
    }

    @Test
    void s3OnlyErrorFromAnotherService_isForeign() {
        InvocationResponse s3 = new InvocationResponse(404, "application/xml",
                "<Error><Code>NoSuchBucket</Code><Message>The specified bucket does not exist</Message></Error>");
        assertThat(classifier.classify(GET, s3)).isEqualTo(CoverageStatus.FOREIGN_ERROR);
    }

    @Test
    void serverError_isServerError() {
        assertThat(classifier.classify(GET, json(500, "InternalFailure", "boom")))
                .isEqualTo(CoverageStatus.SERVER_ERROR);
    }
}
