package io.floci.conformance.util;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline check that the vendored SES v1 / v2 Smithy models load through the
 * lenient {@link SmithyModelLoader} and expose their service operations.
 */
class SmithyModelLoaderTest {

    @Test
    void loadsSesV1() {
        Model m = SmithyModelLoader.loadSesV1();
        ServiceShape svc = m.expectShape(
                ShapeId.from("com.amazonaws.ses#SimpleEmailService"), ServiceShape.class);
        assertThat(svc.getAllOperations()).isNotEmpty();
    }

    @Test
    void loadsSesV2() {
        Model m = SmithyModelLoader.loadSesV2();
        ServiceShape svc = m.expectShape(
                ShapeId.from("com.amazonaws.sesv2#SimpleEmailService_v2"), ServiceShape.class);
        assertThat(svc.getAllOperations()).isNotEmpty();
    }

    @Test
    void loadsS3() {
        Model m = SmithyModelLoader.loadS3();
        ServiceShape svc = m.expectShape(
                ShapeId.from("com.amazonaws.s3#AmazonS3"), ServiceShape.class);
        assertThat(svc.getAllOperations()).isNotEmpty();
    }

    @Test
    void loadsSsm() {
        Model m = SmithyModelLoader.loadSsm();
        ServiceShape svc = m.expectShape(
                ShapeId.from("com.amazonaws.ssm#AmazonSSM"), ServiceShape.class);
        assertThat(svc.getAllOperations()).isNotEmpty();
    }

    @Test
    void loadsDynamoDb() {
        Model m = SmithyModelLoader.loadDynamoDb();
        ServiceShape svc = m.expectShape(
                ShapeId.from("com.amazonaws.dynamodb#DynamoDB_20120810"), ServiceShape.class);
        assertThat(svc.getAllOperations()).isNotEmpty();
    }
}
