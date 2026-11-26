package io.floci.aws.services.s3tables;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3TablesIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String BUCKET_NAME = "s3tables-integration-bucket";
    private static final String NAMESPACE = "analytics";
    private static final String TABLE_NAME = "events";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET_ARN = "arn:aws:s3tables:%s:%s:bucket/%s"
            .formatted(REGION, ACCOUNT_ID, BUCKET_NAME);
    private static final String ENCODED_BUCKET_ARN = URLEncoder.encode(BUCKET_ARN, StandardCharsets.UTF_8);

    private String versionToken;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createsTableBucketThroughTheS3TablesRoute() {
        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "name": "%s" }
                        """.formatted(BUCKET_NAME))
        .when()
                .put("/buckets")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("arn", equalTo(BUCKET_ARN));
    }

    @Test
    @Order(2)
    void managesNamespaceAndTableThroughEncodedBucketArnRoutes() {
        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "namespace": ["%s"] }
                        """.formatted(NAMESPACE))
        .when()
                .put("/namespaces/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("namespace", contains(NAMESPACE))
                .body("tableBucketARN", equalTo(BUCKET_ARN));

        versionToken = s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "name": "%s",
                          "format": "ICEBERG",
                          "metadata": { "iceberg": { "metadataLocation": "s3://warehouse/events/metadata/v1.json" } }
                        }
                        """.formatted(TABLE_NAME))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("tableARN", matchesPattern(Pattern.quote(BUCKET_ARN) + "/table/[0-9a-f-]{36}"))
                .body("versionToken", not(""))
                .extract()
                .path("versionToken");
    }

    @Test
    @Order(3)
    void updatesMetadataLocationUsingOptimisticVersionTokens() {
        String replacementToken = s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v2.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(200)
                .body("metadataLocation", equalTo("s3://warehouse/events/metadata/v2.json"))
                .body("versionToken", not(equalTo(versionToken)))
                .extract()
                .path("versionToken");

        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v3.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        versionToken = replacementToken;
    }

    @Test
    @Order(4)
    void roundTripsTableBucketPolicy() {
        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "resourcePolicy": "{\\\"Version\\\":\\\"2012-10-17\\\"}" }
                        """)
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200);

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("resourcePolicy", equalTo("{\"Version\":\"2012-10-17\"}"));
    }

    @Test
    @Order(5)
    void roundTripsTypedMaintenanceConfigurationMaps() {
        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance/icebergUnreferencedFileRemoval")
        .then()
                .statusCode(200);

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergUnreferencedFileRemoval.status", equalTo("enabled"));

        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME
                        + "/maintenance/icebergCompaction")
        .then()
                .statusCode(200);

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergCompaction.status", equalTo("enabled"));
    }

    @Test
    @Order(6)
    void tagsTheTableBucketAndTheTableOnTheTagRoute() {
        String tableArn = s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/get-table?tableBucketARN=" + ENCODED_BUCKET_ARN + "&namespace=" + NAMESPACE + "&name=" + TABLE_NAME)
        .then()
                .statusCode(200)
                .extract().path("tableARN");
        String encodedTableArn = URLEncoder.encode(tableArn, StandardCharsets.UTF_8);

        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"tags\": { \"team\": \"data\", \"env\": \"dev\" } }")
        .when()
                .post("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .body(equalTo("{}"));
        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"tags\": { \"owner\": \"alice\" } }")
        .when()
                .post("/tag/" + encodedTableArn)
        .then()
                .statusCode(200);

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("tags.team", equalTo("data"))
                .body("tags.env", equalTo("dev"));
        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + encodedTableArn)
        .then()
                .statusCode(200)
                .body("tags.owner", equalTo("alice"))
                .body("tags", not(hasKey("team")));

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .delete("/tag/" + ENCODED_BUCKET_ARN + "?tagKeys=env&tagKeys=absent")
        .then()
                .statusCode(204);
        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .body("tags.team", equalTo("data"))
                .body("tags", not(hasKey("env")));
    }

    @Test
    @Order(7)
    void refusesTagsOnAnUnknownResourceReservedKeysAndMalformedBodies() {
        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + URLEncoder.encode(BUCKET_ARN + "/table/unknown", StandardCharsets.UTF_8))
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The specified table does not exist."));
        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + URLEncoder.encode(BUCKET_ARN + "-missing", StandardCharsets.UTF_8))
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The specified bucket does not exist."));

        s3tables()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"tags\": { \"aws:team\": \"data\" } }")
        .when()
                .post("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Tags beginning with 'aws:' are reserved for system use."));

        for (String body : new String[] {"{ \"tags\": { \"rejected\": null } }", "{ \"tags\": { \"rejected\": 1 } }",
                "{ \"tags\": [\"rejected\"] }", "{ \"tags\": \"rejected\" }", "{}"}) {
            s3tables()
                    .urlEncodingEnabled(false)
                    .contentType(JSON_CONTENT_TYPE)
                    .body(body)
            .when()
                    .post("/tag/" + ENCODED_BUCKET_ARN)
            .then()
                    .statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
        }
        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .get("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .body("tags", not(hasKey("rejected")));

        s3tables()
                .urlEncodingEnabled(false)
        .when()
                .delete("/tag/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    private static RequestSpecification s3tables() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3tables/aws4_request");
    }
}
