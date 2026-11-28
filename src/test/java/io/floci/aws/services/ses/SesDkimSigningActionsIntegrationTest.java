package io.floci.aws.services.ses;

import io.floci.aws.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.floci.aws.services.ses.SesV2SerializationErrors.EXPECTED_NULL;
import static io.floci.aws.services.ses.SesV2SerializationErrors.LIST;
import static io.floci.aws.services.ses.SesV2SerializationErrors.NUMBER;
import static io.floci.aws.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.floci.aws.services.ses.SesV2SerializationErrors.assertSerializationException;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests for the DKIM actions: v1 {@code VerifyDomainDkim} / {@code SetIdentityDkimEnabled}
 * and v2 {@code PutEmailIdentityDkimSigningAttributes}, plus the email-inherits-parent-domain DKIM
 * behavior. Verified against real AWS (tokens stable on VerifyDomainDkim; email DKIM mirrors the
 * parent domain; DKIM status tracks DNS detection, not the enabled flag).
 */
@QuarkusTest
class SesDkimSigningActionsIntegrationTest {

    private RequestSpecification v1(String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", SesV1Requests.AUTH).formParam("Action", action);
    }

    @Test
    void verifyDomainDkim_returnsThreeStableTokens_andRegistersDomain() {
        String domain = "dkim-verify.floci.test";
        List<String> first = XmlParser.extractAll(v1("VerifyDomainDkim").formParam("Domain", domain)
        .when().post("/").then().statusCode(200).extract().asString(), "member");
        assertEquals(3, first.size());

        // Stable across calls (AWS does not regenerate).
        List<String> second = XmlParser.extractAll(v1("VerifyDomainDkim").formParam("Domain", domain)
        .when().post("/").then().statusCode(200).extract().asString(), "member");
        assertEquals(first, second);

        // The domain is now a DKIM identity, visible via GetIdentityDkimAttributes.
        v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<key>" + domain + "</key>"))
                .body(containsString("<DkimEnabled>true</DkimEnabled>"));
    }

    @Test
    void setIdentityDkimEnabled_togglesFlag_andUnknownIdentityErrors() {
        String domain = "dkim-toggle.floci.test";
        v1("VerifyDomainDkim").formParam("Domain", domain).when().post("/").then().statusCode(200);

        v1("SetIdentityDkimEnabled").formParam("Identity", domain).formParam("DkimEnabled", "false")
        .when().post("/").then().statusCode(200)
                .body(containsString("SetIdentityDkimEnabledResponse"));

        v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DkimEnabled>false</DkimEnabled>"));

        // Unknown identity -> v1-native InvalidParameterValue.
        String unknown = v1("SetIdentityDkimEnabled").formParam("Identity", "unknown-dkim.floci.test")
                .formParam("DkimEnabled", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .extract().asString();
        assertEquals("Domain <unknown-dkim.floci.test> is not verified for DKIM signing.",
                XmlParser.extractFirst(unknown, "Message", null));
    }

    @Test
    void verifyDomainIdentity_alone_readsNoEasyDkimOnV1AndV2() {
        String domain = "dkim-v1-only-read.floci.test";
        v1("VerifyDomainIdentity").formParam("Domain", domain).when().post("/").then().statusCode(200);

        String v1Attributes = v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DkimEnabled>false</DkimEnabled>"))
                .body(containsString("<DkimVerificationStatus>NotStarted</DkimVerificationStatus>"))
                .extract().asString();
        assertEquals(List.of(), XmlParser.extractAll(v1Attributes, "member"));

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(false))
                .body("DkimAttributes.Status", equalTo("NOT_STARTED"))
                .body("DkimAttributes.SigningAttributesOrigin", equalTo("AWS_SES"))
                .body("DkimAttributes.NextSigningKeyLength", equalTo("RSA_1024_BIT"))
                .body("DkimAttributes", not(hasKey("Tokens")))
                .body("DkimAttributes", not(hasKey("CurrentSigningKeyLength")))
                .body("DkimAttributes", not(hasKey("SigningHostedZone")))
                .body("DkimAttributes", not(hasKey("LastKeyGenerationTimestamp")));
    }

    @Test
    void verifyDomainIdentity_alone_anAddressUnderTheDomainReadsNoEasyDkim() {
        String domain = "dkim-v1-only-address.floci.test";
        String address = "alice@" + domain;
        v1("VerifyDomainIdentity").formParam("Domain", domain).when().post("/").then().statusCode(200);
        SesV2Requests.v2().body("{\"EmailIdentity\":\"" + address + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        String v1Attributes = v1("GetIdentityDkimAttributes").formParam("Identities.member.1", address)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DkimEnabled>false</DkimEnabled>"))
                .body(containsString("<DkimVerificationStatus>NotStarted</DkimVerificationStatus>"))
                .extract().asString();
        assertEquals(List.of(), XmlParser.extractAll(v1Attributes, "member"));

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + address).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(false))
                .body("DkimAttributes.Status", equalTo("NOT_STARTED"))
                .body("DkimAttributes", not(hasKey("Tokens")))
                .body("DkimAttributes", not(hasKey("SigningHostedZone")));
    }

    @Test
    void verifyDomainIdentity_alone_refusesTheDkimToggleOnV1AndV2() {
        String domain = "dkim-v1-only-toggle.floci.test";
        v1("VerifyDomainIdentity").formParam("Domain", domain).when().post("/").then().statusCode(200);
        String refusal = "Domain <" + domain + "> is not verified for DKIM signing.";

        for (String identity : List.of(domain, "alice@" + domain)) {
            for (String enabled : List.of("true", "false")) {
                String body = v1("SetIdentityDkimEnabled").formParam("Identity", identity)
                        .formParam("DkimEnabled", enabled)
                .when().post("/").then().statusCode(400).extract().asString();
                assertEquals("InvalidParameterValue", XmlParser.extractFirst(body, "Code", null));
                assertEquals(refusal, XmlParser.extractFirst(body, "Message", null));

                SesV2Requests.v2().body("{\"SigningEnabled\":" + enabled + "}")
                .when().put("/v2/email/identities/" + identity + "/dkim")
                .then().statusCode(400).body("__type", equalTo("BadRequestException"))
                        .body("message", equalTo(refusal));
            }
        }
    }

    @Test
    void verifyDomainDkim_startsEasyDkimForADomainVerifyDomainIdentityRegistered() {
        String domain = "dkim-v1-then-dkim.floci.test";
        v1("VerifyDomainIdentity").formParam("Domain", domain).when().post("/").then().statusCode(200);

        List<String> tokens = XmlParser.extractAll(v1("VerifyDomainDkim").formParam("Domain", domain)
        .when().post("/").then().statusCode(200).extract().asString(), "member");
        assertEquals(3, tokens.size());

        v1("SetIdentityDkimEnabled").formParam("Identity", domain).formParam("DkimEnabled", "true")
        .when().post("/").then().statusCode(200);
        SesV2Requests.v2().body("{\"SigningEnabled\":false}")
        .when().put("/v2/email/identities/" + domain + "/dkim").then().statusCode(200);

        v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DkimEnabled>false</DkimEnabled>"))
                .body(containsString("<DkimVerificationStatus>Pending</DkimVerificationStatus>"));
        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.Status", equalTo("PENDING"))
                .body("DkimAttributes.Tokens", equalTo(tokens));
    }

    @Test
    void createEmailIdentity_domain_acceptsTheV1DkimToggle() {
        String domain = "dkim-v2-created.floci.test";
        SesV2Requests.v2().body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200)
                .body("DkimAttributes.Tokens", hasSize(3));

        v1("SetIdentityDkimEnabled").formParam("Identity", domain).formParam("DkimEnabled", "false")
        .when().post("/").then().statusCode(200);

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(false))
                .body("DkimAttributes.NextSigningKeyLength", equalTo("RSA_2048_BIT"))
                .body("DkimAttributes.CurrentSigningKeyLength", equalTo("RSA_2048_BIT"));
    }

    @Test
    void putDkimSigningAttributes_awsSes_regeneratesTokensOnKeyLengthChange() {
        String domain = "dkim-signing.floci.test";
        List<String> before = SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200)
                .extract().path("DkimAttributes.Tokens");

        // Change the key length (default is RSA_2048_BIT) -> tokens regenerate.
        Response resp = SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\","
                        + "\"SigningAttributes\":{\"NextSigningKeyLength\":\"RSA_1024_BIT\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing").then().statusCode(200)
                .body("DkimTokens", hasSize(3))
                // Regenerated tokens reset the status: the new CNAMEs are not yet detected in DNS.
                .body("DkimStatus", equalTo("PENDING")).extract().response();
        List<String> after = resp.path("DkimTokens");
        assertNotEquals(before, after, "changing the key length must regenerate the tokens");

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.CurrentSigningKeyLength", equalTo("RSA_1024_BIT"))
                .body("DkimAttributes.SigningHostedZone", equalTo("dkim.amazonses.com"))
                .body("DkimAttributes.Tokens", equalTo(after));
    }

    @Test
    void putDkimSigningAttributes_emailIdentity_returns400() {
        // DKIM signing attributes are domain-level; an email-shaped identity is rejected.
        SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\"}")
        .when().put("/v2/email/identities/bob@dkim-emailsigning.floci.test/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The EmailIdentity value must be a valid domain."));
    }

    @Test
    void putDkimSigningAttributes_blankIdentity_returns400() {
        // A blank/whitespace identity is rejected with the same 400 "must be a valid domain" as an
        // email-shaped one (verified against AWS), rather than falling through to a 404.
        given().urlEncodingEnabled(false).contentType("application/json").header("Authorization", SesV2Requests.AUTH)
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\"}")
        .when().put("/v2/email/identities/%20/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The EmailIdentity value must be a valid domain."));
    }

    @Test
    void putDkimSigningAttributes_unknownIdentity_returns404() {
        SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\"}")
        .when().put("/v2/email/identities/ghost-signing.floci.test/dkim/signing")
        .then().statusCode(404).body("__type", equalTo("NotFoundException"))
                .body("message", equalTo(
                        "The specified email identity, ghost-signing.floci.test, does not exist."));
    }

    @Test
    void putDkimSigningAttributes_externalWithBlankPrivateKey_returns400() {
        String domain = "dkim-external.floci.test";
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"EXTERNAL\",\"SigningAttributes\":"
                        + "{\"DomainSigningSelector\":\"sel\",\"DomainSigningPrivateKey\":\"\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void setIdentityDkimEnabled_missingIdentity_returns400() {
        v1("SetIdentityDkimEnabled").formParam("DkimEnabled", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Identity is required."));
    }

    @Test
    void verifyDomainDkim_whitespaceDomain_returns400() {
        v1("VerifyDomainDkim").formParam("Domain", " dkim-ws.floci.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("whitespace"));
    }

    @Test
    void verifyDomainDkim_emailShapedInput_returns400() {
        v1("VerifyDomainDkim").formParam("Domain", "bob@dkim-bad.floci.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void putDkimSigningAttributes_invalidKeyLength_returns400() {
        String domain = "dkim-badkeylen.floci.test";
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\","
                        + "\"SigningAttributes\":{\"NextSigningKeyLength\":\"RSA_4096_BIT\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void putDkimSigningAttributes_external_clearsTokensAndResetsStatusToPending() {
        String domain = "dkim-byodkim.floci.test";
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        SesV2Requests.v2()
                .body("{\"SigningAttributesOrigin\":\"EXTERNAL\",\"SigningAttributes\":"
                        + "{\"DomainSigningSelector\":\"sel\",\"DomainSigningPrivateKey\":\"key\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing").then().statusCode(200)
                .body("DkimStatus", equalTo("PENDING"))
                .body("DkimTokens", hasSize(0));

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.Status", equalTo("PENDING"))
                .body("DkimAttributes.SigningAttributesOrigin", equalTo("EXTERNAL"))
                .body("DkimAttributes.SigningHostedZone", nullValue())
                .body("DkimAttributes.Tokens", hasSize(0));
    }

    @Test
    void putDkimSigningAttributes_nonObjectBody_returns400() {
        SesV2Requests.v2().body("[]")
        .when().put("/v2/email/identities/dkim-nonobj.floci.test/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Start of list found where not expected"));
    }

    // The identity does not exist and some cases send an invalid origin: AWS reads the body
    // against the shapes before either check.
    static Stream<Arguments> putDkimSigningAttributesWrongMemberTypeCases() {
        return Stream.of(
                Arguments.of("stringSigningAttributes",
                        "{\"SigningAttributesOrigin\": \"AWS_SES\", \"SigningAttributes\": \"x\"}", EXPECTED_NULL),
                Arguments.of("stringSigningAttributesWithInvalidOrigin",
                        "{\"SigningAttributesOrigin\": \"BAD\", \"SigningAttributes\": \"x\"}", EXPECTED_NULL),
                Arguments.of("arraySigningAttributes",
                        "{\"SigningAttributesOrigin\": \"AWS_SES\", \"SigningAttributes\": []}", LIST),
                Arguments.of("arrayNextSigningKeyLength",
                        "{\"SigningAttributesOrigin\": \"AWS_SES\", "
                                + "\"SigningAttributes\": {\"NextSigningKeyLength\": []}}", LIST),
                Arguments.of("objectDomainSigningSelector",
                        "{\"SigningAttributesOrigin\": \"EXTERNAL\", "
                                + "\"SigningAttributes\": {\"DomainSigningSelector\": {}}}", STRUCTURE),
                Arguments.of("numericOrigin", "{\"SigningAttributesOrigin\": 1}", NUMBER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putDkimSigningAttributesWrongMemberTypeCases")
    void putDkimSigningAttributes_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("PUT", "/v2/email/identities/dkim-missing.floci.test/dkim/signing",
                body, message);
    }

    @Test
    void putEmailIdentityDkimAttributes_existingEmailWithRegisteredDomain_isNoOp() {
        String domain = "dkim-emailtoggle.floci.test";
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"bob@" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        // Toggling DKIM on the email is a no-op (DKIM is domain-controlled) — 200, nothing changes.
        SesV2Requests.v2()
                .body("{\"SigningEnabled\": false}")
        .when().put("/v2/email/identities/bob@" + domain + "/dkim").then().statusCode(200);

        // The email still reports the domain's DKIM (enabled), and the domain is untouched.
        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/bob@" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true));
        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true));
    }

    @Test
    void emailIdentity_inheritsParentDomainDkim() {
        String domain = "dkim-inherit.floci.test";
        List<String> domainTokens = SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200)
                .extract().path("DkimAttributes.Tokens");

        // An email under that domain inherits the domain's DKIM (tokens, enabled, pending status).
        SesV2Requests.v2()
                .body("{\"EmailIdentity\":\"bob@" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/identities/bob@" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true))
                .body("DkimAttributes.Status", equalTo("PENDING"))
                .body("DkimAttributes.SigningHostedZone", equalTo("dkim.amazonses.com"))
                .body("DkimAttributes.Tokens", equalTo(domainTokens))
                .body("DkimAttributes.Tokens", not(hasSize(0)));
    }
}
