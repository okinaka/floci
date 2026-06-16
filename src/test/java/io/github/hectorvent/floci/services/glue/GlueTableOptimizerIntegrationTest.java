package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueTableOptimizerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String CATALOG_ID = "000000000000";
    private static final String DATABASE = "optimizer-db";
    private static final String TABLE = "optimizer-table";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/email-archive-summaries-table-optimizer-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTableOptimizer() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("{ \"DatabaseInput\": { \"Name\": \"" + DATABASE + "\" } }")
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateTable")
                .body("{ \"DatabaseName\": \"" + DATABASE + "\", \"TableInput\": { \"Name\": \"" + TABLE + "\" } }")
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction",
                          "TableOptimizerConfiguration": {
                            "roleArn": "%s",
                            "enabled": true
                          }
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE, ROLE_ARN))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(2)
    void getTableOptimizerReturnsConfiguration() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction"
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("DatabaseName", equalTo(DATABASE))
                .body("TableName", equalTo(TABLE))
                .body("TableOptimizer.type", equalTo("compaction"))
                .body("TableOptimizer.configuration.roleArn", equalTo(ROLE_ARN))
                .body("TableOptimizer.configuration.enabled", equalTo(true));
    }

    @Test
    @Order(3)
    void updateTableOptimizerChangesConfiguration() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.UpdateTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction",
                          "TableOptimizerConfiguration": {
                            "roleArn": "%s",
                            "enabled": false
                          }
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE, ROLE_ARN))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction"
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("TableOptimizer.configuration.enabled", equalTo(false));
    }

    @Test
    @Order(4)
    void deleteTableOptimizerRemovesIt() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction"
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTableOptimizer")
                .body("""
                        {
                          "CatalogId": "%s",
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Type": "compaction"
                        }
                        """.formatted(CATALOG_ID, DATABASE, TABLE))
        .when().post("/")
        .then().statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("EntityNotFoundException"));
    }
}
