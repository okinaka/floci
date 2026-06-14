package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * SES V2 report endpoints whose data AWS derives from real sending
 * infrastructure (blacklist lookups, reputation tracking). A local account has
 * no such history, so the faithful response is an empty collection. Behavior
 * verified against real AWS SES V2 on 2026-06-14.
 */
@QuarkusTest
class SesReportStubsV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    void getBlacklistReports_returnsEmptyMap() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/deliverability-dashboard/blacklist-report?BlacklistItemNames=192.0.2.10")
        .then()
            .statusCode(200)
            .body("BlacklistReport", anEmptyMap());
    }

    @Test
    void getBlacklistReports_missingItems_returns400() {
        // AWS rejects a missing/empty BlacklistItemNames with this message.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/deliverability-dashboard/blacklist-report")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Items to query must be provided."));
    }

    @Test
    void listReputationEntities_returnsEmptyList() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/v2/email/reputation/entities")
        .then()
            .statusCode(200)
            .body("ReputationEntities", hasSize(0));
    }
}
