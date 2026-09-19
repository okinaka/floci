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

    @Test
    void putBucketReplication_repeats_flattened_rules_without_member_wrapper() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#PutBucketReplication"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        ObjectNode config = input.putObject("ReplicationConfiguration");
        config.put("Role", "arn:aws:iam::000000000000:role/r");
        ObjectNode rule = config.putArray("Rules").addObject();
        rule.put("Status", "Enabled");
        rule.putObject("Destination").put("Bucket", "arn:aws:s3:::dest");
        ObjectNode and = rule.putObject("Filter").putObject("And");
        and.put("Prefix", "p");
        and.putArray("Tags").addObject().put("Key", "k").put("Value", "v");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        // Rules is @xmlFlattened @xmlName("Rule"), Tags is @xmlFlattened @xmlName("Tag"):
        // S3 rejects a <Rules><member> wrapper with MalformedXML.
        assertThat(v.rawBody()).isEqualTo(
                "<ReplicationConfiguration>"
                        + "<Role>arn:aws:iam::000000000000:role/r</Role>"
                        + "<Rule>"
                        + "<Filter><And><Prefix>p</Prefix><Tag><Key>k</Key><Value>v</Value></Tag></And></Filter>"
                        + "<Status>Enabled</Status>"
                        + "<Destination><Bucket>arn:aws:s3:::dest</Bucket></Destination>"
                        + "</Rule>"
                        + "</ReplicationConfiguration>");
        assertThat(v.rawBody()).doesNotContain("<member>").doesNotContain("<Rules>").doesNotContain("<Tags>");
    }

    @Test
    void putBucketMetricsConfiguration_writes_the_present_union_member() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#PutBucketMetricsConfiguration"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        input.put("Id", "m1");
        ObjectNode config = input.putObject("MetricsConfiguration");
        config.put("Id", "m1");
        config.putObject("Filter").put("Prefix", "logs/");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        // MetricsFilter is a union; an empty <Filter/> is MalformedXML on S3.
        assertThat(v.rawBody()).isEqualTo(
                "<MetricsConfiguration><Id>m1</Id><Filter><Prefix>logs/</Prefix></Filter></MetricsConfiguration>");
    }

    @Test
    void putBucketAcl_keeps_wrapper_for_non_flattened_lists() {
        OperationShape op = S3.expectShape(
                ShapeId.from("com.amazonaws.s3#PutBucketAcl"), OperationShape.class);
        ObjectNode input = NODES.objectNode();
        input.put("Bucket", "cov-probe-bucket");
        ObjectNode policy = input.putObject("AccessControlPolicy");
        ObjectNode grant = policy.putArray("Grants").addObject();
        grant.put("Permission", "READ");
        grant.putObject("Grantee").put("Type", "Group").put("URI", "http://acs.amazonaws.com/groups/global/AllUsers");

        Variant v = new RestXmlEncoder(S3).encode(new GeneratedCase(
                op, "test", input, ExpectedOutcome.SUCCESS, null));

        // Grants is @xmlName("AccessControlList") with @xmlName("Grant") entries, not flattened,
        // and Grantee.Type is @xmlAttribute("xsi:type"), so it rides on the element.
        assertThat(v.rawBody()).contains("<AccessControlList><Grant>").contains("</Grant></AccessControlList>");
        assertThat(v.rawBody()).contains(
                "<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">"
                        + "<URI>http://acs.amazonaws.com/groups/global/AllUsers</URI></Grantee>");
        assertThat(v.rawBody()).doesNotContain("<member>").doesNotContain("<Type>").doesNotContain("<xsi:type>");
    }
}
