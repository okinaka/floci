package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityAttributesV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createEmailIdentity_setsUpDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "v2-attrs.floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void putEmailIdentityMailFromAttributes_setsDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "MailFromDomain": "mail.v2-attrs.floci.test",
                  "BehaviorOnMxFailure": "REJECT_MESSAGE"
                }
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void getEmailIdentity_includesMailFromAttributes() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .body("MailFromAttributes.MailFromDomain", equalTo("mail.v2-attrs.floci.test"))
            .body("MailFromAttributes.MailFromDomainStatus", equalTo("SUCCESS"))
            .body("MailFromAttributes.BehaviorOnMxFailure", equalTo("REJECT_MESSAGE"));
    }

    @Test
    @Order(4)
    void putEmailIdentityMailFromAttributes_emptyDomain_clears() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": ""}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .body("MailFromAttributes.MailFromDomain", equalTo(""))
            .body("MailFromAttributes.MailFromDomainStatus", equalTo("NOT_STARTED"));
    }

    @Test
    @Order(5)
    void putEmailIdentityMailFromAttributes_unknownIdentity_returnsBadRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": "mail.ghost.floci.test"}
                """)
        .when()
            .put("/v2/email/identities/ghost.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Identity <ghost.floci.test> does not exist."));
    }

    @Test
    @Order(6)
    void putEmailIdentityMailFromAttributes_invalidJson_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("[1,2,3]")
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void putEmailIdentityMailFromAttributes_missingBody_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(8)
    void putEmailIdentityMailFromAttributes_missingMailFromDomainField_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"BehaviorOnMxFailure": "USE_DEFAULT_VALUE"}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(9)
    void putEmailIdentityMailFromAttributes_mailFromDomainAsObject_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": {"foo": "bar"}}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(10)
    void putEmailIdentityMailFromAttributes_unknownBehavior_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "MailFromDomain": "mail.v2-attrs.floci.test",
                  "BehaviorOnMxFailure": "BOGUS_VALUE"
                }
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .body("message", org.hamcrest.Matchers.containsString(
                    "Member must satisfy enum value set: [REJECT_MESSAGE, USE_DEFAULT_VALUE]"))
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }
}
