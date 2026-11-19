package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SesV1UnsupportedOperationIntegrationTest {

    private static final String V1_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @Test
    void namesTheOperation() {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "NoSuchOperation")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
            .body("ErrorResponse.Error.Message", equalTo("Operation NoSuchOperation is not supported by SES."));
    }

    // the action is request input like any quoted value, so it gets the same treatment in the message
    @Test
    void spellsOutACharacterXmlForbids() {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "No\tSuch￾Operation")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
            .body("ErrorResponse.Error.Message",
                    equalTo("Operation No0x0009Such0xfffeOperation is not supported by SES."));
    }
}
