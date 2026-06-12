package io.floci.conformance.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link ShapeValidator}: JSON body validation against
 * Smithy output structures (happy path and type mismatch), awsQuery XML
 * handling (response-wrapper unwrap, empty elements, {@code <entry>}-encoded
 * maps with single and multiple entries).
 */
class ShapeValidatorTest {

    private static final Model V1 = SmithyModelLoader.loadSesV1();
    private static final Model V2 = SmithyModelLoader.loadSesV2();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XmlMapper XML = new XmlMapper();

    @Test
    void v2_validates_well_formed_response() throws Exception {
        StructureShape out = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#ListEmailIdentitiesResponse"),
                StructureShape.class);
        var body = JSON.readTree("""
                {"EmailIdentities": [], "NextToken": "abc"}""");

        ShapeValidator v = new ShapeValidator(V2, false);
        assertThat(v.validate(body, out).ok()).isTrue();
    }

    @Test
    void v2_flags_wrong_type() throws Exception {
        StructureShape out = V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#ListEmailIdentitiesResponse"),
                StructureShape.class);
        // EmailIdentities should be an array, not a string.
        var body = JSON.readTree("""
                {"EmailIdentities": "not-an-array"}""");

        ShapeValidator.Result result = new ShapeValidator(V2, false).validate(body, out);
        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.path().contains("EmailIdentities"));
    }

    @Test
    void v1_unwraps_xml_response_wrapper() throws Exception {
        String xml = """
                <ListIdentitiesResponse xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
                  <ListIdentitiesResult>
                    <Identities>
                      <member>example.com</member>
                    </Identities>
                  </ListIdentitiesResult>
                  <ResponseMetadata>
                    <RequestId>abc-123</RequestId>
                  </ResponseMetadata>
                </ListIdentitiesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "ListIdentities");

        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentitiesResponse"),
                StructureShape.class);

        ShapeValidator.Result result = new ShapeValidator(V1, true).validate(unwrapped, out);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void v1_xml_empty_list_is_ok() throws Exception {
        String xml = """
                <ListIdentitiesResponse><ListIdentitiesResult>
                  <Identities/>
                </ListIdentitiesResult></ListIdentitiesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "ListIdentities");

        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#ListIdentitiesResponse"),
                StructureShape.class);
        assertThat(new ShapeValidator(V1, true).validate(unwrapped, out).ok()).isTrue();
    }

    @Test
    void v1_xml_map_with_entry_key_value_wrapper_validates() throws Exception {
        // AWS Query map XML — single entry case.
        String xml = """
                <GetIdentityDkimAttributesResponse>
                  <GetIdentityDkimAttributesResult>
                    <DkimAttributes>
                      <entry>
                        <key>example.com</key>
                        <value>
                          <DkimEnabled>false</DkimEnabled>
                          <DkimVerificationStatus>NotStarted</DkimVerificationStatus>
                          <DkimTokens></DkimTokens>
                        </value>
                      </entry>
                    </DkimAttributes>
                  </GetIdentityDkimAttributesResult>
                </GetIdentityDkimAttributesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "GetIdentityDkimAttributes");
        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityDkimAttributesResponse"),
                StructureShape.class);
        ShapeValidator.Result result = new ShapeValidator(V1, true).validate(unwrapped, out);
        // After the fix, this should report no shape issues for the entry encoding.
        // (The map value's own @required members must still be present, which
        // they are in this sample.)
        assertThat(result.ok())
                .as("expected ok but got: %s", result.issues())
                .isTrue();
    }

    @Test
    void v1_xml_map_with_multiple_entries_validates() throws Exception {
        String xml = """
                <GetIdentityDkimAttributesResponse>
                  <GetIdentityDkimAttributesResult>
                    <DkimAttributes>
                      <entry>
                        <key>a.example.com</key>
                        <value>
                          <DkimEnabled>true</DkimEnabled>
                          <DkimVerificationStatus>Success</DkimVerificationStatus>
                          <DkimTokens><member>t1</member></DkimTokens>
                        </value>
                      </entry>
                      <entry>
                        <key>b.example.com</key>
                        <value>
                          <DkimEnabled>false</DkimEnabled>
                          <DkimVerificationStatus>NotStarted</DkimVerificationStatus>
                          <DkimTokens></DkimTokens>
                        </value>
                      </entry>
                    </DkimAttributes>
                  </GetIdentityDkimAttributesResult>
                </GetIdentityDkimAttributesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "GetIdentityDkimAttributes");
        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityDkimAttributesResponse"),
                StructureShape.class);
        ShapeValidator.Result result = new ShapeValidator(V1, true).validate(unwrapped, out);
        assertThat(result.ok())
                .as("expected ok but got: %s", result.issues())
                .isTrue();
    }

    @Test
    void overdeclared_required_topics_are_not_enforced() throws Exception {
        // Matches what real AWS returns for an identity with no SNS topics
        // configured (verified 2026-06-12): ForwardingEnabled + HeadersIn*
        // present, all *Topic fields omitted despite their @required.
        String xml = """
                <GetIdentityNotificationAttributesResponse>
                  <GetIdentityNotificationAttributesResult>
                    <NotificationAttributes>
                      <entry>
                        <key>probe@example.com</key>
                        <value>
                          <ForwardingEnabled>true</ForwardingEnabled>
                          <HeadersInBounceNotificationsEnabled>false</HeadersInBounceNotificationsEnabled>
                          <HeadersInComplaintNotificationsEnabled>false</HeadersInComplaintNotificationsEnabled>
                          <HeadersInDeliveryNotificationsEnabled>false</HeadersInDeliveryNotificationsEnabled>
                        </value>
                      </entry>
                    </NotificationAttributes>
                  </GetIdentityNotificationAttributesResult>
                </GetIdentityNotificationAttributesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "GetIdentityNotificationAttributes");
        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityNotificationAttributesResponse"),
                StructureShape.class);
        ShapeValidator.Result result = new ShapeValidator(V1, true).validate(unwrapped, out);
        assertThat(result.ok())
                .as("AWS-faithful response should validate, got: %s", result.issues())
                .isTrue();
    }

    @Test
    void truly_required_forwardingEnabled_is_still_enforced() throws Exception {
        // An entirely empty value (Floci 1.5.2x behavior) still violates the
        // genuinely-required ForwardingEnabled.
        String xml = """
                <GetIdentityNotificationAttributesResponse>
                  <GetIdentityNotificationAttributesResult>
                    <NotificationAttributes>
                      <entry>
                        <key>probe@example.com</key>
                        <value></value>
                      </entry>
                    </NotificationAttributes>
                  </GetIdentityNotificationAttributesResult>
                </GetIdentityNotificationAttributesResponse>""";
        var root = XML.readTree(xml.getBytes());
        var unwrapped = ShapeValidator.unwrapXmlResult(root, "GetIdentityNotificationAttributes");
        StructureShape out = V1.expectShape(
                ShapeId.from("com.amazonaws.ses#GetIdentityNotificationAttributesResponse"),
                StructureShape.class);
        ShapeValidator.Result result = new ShapeValidator(V1, true).validate(unwrapped, out);
        assertThat(result.ok()).isFalse();
        assertThat(result.issues())
                .anyMatch(i -> i.path().contains("ForwardingEnabled"));
        assertThat(result.issues())
                .noneMatch(i -> i.path().contains("BounceTopic"));
    }

    @Test
    void unwrap_returns_root_when_no_response_wrapper() throws Exception {
        var root = JSON.readTree("{\"foo\": \"bar\"}");
        assertThat(ShapeValidator.unwrapXmlResult(root, "AnyOp")).isEqualTo(root);
    }
}
