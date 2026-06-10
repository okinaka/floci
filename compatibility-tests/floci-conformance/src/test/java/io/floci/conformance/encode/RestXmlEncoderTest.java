package io.floci.conformance.encode;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link RestXmlEncoder} against the S3 model:
 * path/query/header splitting, structure payload serialization with
 * {@code @xmlName} root and namespace, and blob payload pass-through.
 */
class RestXmlEncoderTest {

    private static final Model S3 = SmithyModelLoader.loadS3();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void getObject_splits_bucket_and_key_into_path() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#GetObject"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        input.put("Key", "cov-probe-key");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.pathParams()).containsEntry("Bucket", "cov-probe-bucket");
        assertThat(v.pathParams()).containsEntry("Key", "cov-probe-key");
        assertThat(v.rawBody()).isNull();
    }

    @Test
    void createBucket_serializes_payload_struct_as_xml_with_namespace() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#CreateBucket"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        ObjectNode config = input.putObject("CreateBucketConfiguration");
        config.put("LocationConstraint", "us-west-2");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.pathParams()).containsEntry("Bucket", "cov-probe-bucket");
        assertThat(v.rawBody()).contains("<CreateBucketConfiguration");
        assertThat(v.rawBody()).contains("<LocationConstraint>us-west-2</LocationConstraint>");
        assertThat(v.rawContentType()).isEqualTo("application/xml");
    }

    @Test
    void putObject_blob_payload_decodes_to_raw_body() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#PutObject"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        input.put("Key", "cov-probe-key");
        // "cov-probe-x" in base64
        input.put("Body", "Y292LXByb2JlLXg=");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.rawBody()).isEqualTo("cov-probe-x");
        assertThat(v.rawContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void header_bound_members_go_to_headers() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#PutObject"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        input.put("Key", "cov-probe-key");
        input.put("ContentType", "text/plain");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        assertThat(v.headers()).containsEntry("Content-Type", "text/plain");
    }
}
