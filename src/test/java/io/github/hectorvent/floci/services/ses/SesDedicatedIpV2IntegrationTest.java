package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests for the SES V2 dedicated-IP (IP-level) endpoints. Floci does
 * not model leased dedicated IPs, so an account has none: GetDedicatedIps is
 * empty and any IP-targeted operation reports the IP as not found, matching real
 * AWS for an account with no leased IPs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesDedicatedIpV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TEST_IP = "192.0.2.1";

    @Test
    @Order(1)
    void getDedicatedIps_empty() {
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ips")
        .then().statusCode(200)
            .body("DedicatedIps", hasSize(0));
    }

    @Test
    @Order(2)
    void getDedicatedIp_notFound() {
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ips/" + TEST_IP)
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Could not find dedicated IP <" + TEST_IP + "> under this account."));
    }

    @Test
    @Order(3)
    void putDedicatedIpInPool_ipNotFound() {
        // Even with an existing destination pool, the IP is checked first.
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"dip-scaling-pool\"}")
        .when().post("/v2/email/dedicated-ip-pools").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"DestinationPoolName\": \"dip-scaling-pool\"}")
        .when().put("/v2/email/dedicated-ips/" + TEST_IP + "/pool")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Could not find dedicated IP <" + TEST_IP + "> under this account."));
    }

    @Test
    @Order(4)
    void putDedicatedIpWarmupAttributes_ipNotFound() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"WarmupPercentage\": 50}")
        .when().put("/v2/email/dedicated-ips/" + TEST_IP + "/warmup")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Could not find dedicated IP <" + TEST_IP + "> under this account."));
    }

    @Test
    @Order(5)
    void putDedicatedIpPoolScalingAttributes_updatesPool() {
        // The pool from Order 3 starts STANDARD; switch it to MANAGED.
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ScalingMode\": \"MANAGED\"}")
        .when().put("/v2/email/dedicated-ip-pools/dip-scaling-pool/scaling")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools/dip-scaling-pool")
        .then().statusCode(200)
            .body("DedicatedIpPool.ScalingMode", equalTo("MANAGED"));
    }

    @Test
    @Order(6)
    void putDedicatedIpPoolScalingAttributes_invalidMode_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ScalingMode\": \"NONSENSE\"}")
        .when().put("/v2/email/dedicated-ip-pools/dip-scaling-pool/scaling")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("The ScalingMode parameter is invalid."));
    }

    @Test
    @Order(7)
    void putDedicatedIpPoolScalingAttributes_unknownPool_returns404() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ScalingMode\": \"STANDARD\"}")
        .when().put("/v2/email/dedicated-ip-pools/dip-ghost-pool/scaling")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("The requested pool <dip-ghost-pool> does not exist."));
    }

    @Test
    @Order(8)
    void accountDedicatedIpAutoWarmup_defaultsTrue_andTogglable() {
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/account")
        .then().statusCode(200)
            .body("DedicatedIpAutoWarmupEnabled", equalTo(true));

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"AutoWarmupEnabled\": false}")
        .when().put("/v2/email/account/dedicated-ips/warmup")
        .then().statusCode(200);

        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/account")
        .then().statusCode(200)
            .body("DedicatedIpAutoWarmupEnabled", equalTo(false));
    }
}
