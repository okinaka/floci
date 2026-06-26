package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
class GlueIcebergUpdateTableIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateTableWithIcebergSchemaEvolution() {
        String databaseName = "iceberg-update-db-" + UUID.randomUUID().toString().substring(0, 8);
        String tableName = "summaries";
        String location = "s3://floci-glue-catalog/" + databaseName + "/" + tableName;

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("{ \"DatabaseInput\": { \"Name\": \"%s\" } }".formatted(databaseName))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateTable")
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableInput": { "Name": "%s", "TableType": "EXTERNAL_TABLE" },
                          "OpenTableFormatInput": {
                            "IcebergInput": {
                              "MetadataOperation": "CREATE",
                              "Version": "2",
                              "CreateIcebergTableInput": {
                                "Location": "%s",
                                "Schema": {
                                  "Type": "struct",
                                  "Fields": [ { "Id": 1, "Name": "id", "Required": true, "Type": "uuid" } ]
                                }
                              }
                            }
                          }
                        }
                        """.formatted(databaseName, tableName, location))
        .when().post("/").then().statusCode(200);

        // Evolve the schema via UpdateTable + UpdateOpenTableFormatInput.
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.UpdateTable")
                .body("""
                        {
                          "DatabaseName": "%s",
                          "Name": "%s",
                          "UpdateOpenTableFormatInput": {
                            "UpdateIcebergInput": {
                              "UpdateIcebergTableInput": {
                                "Updates": [
                                  {
                                    "Schema": {
                                      "Type": "struct",
                                      "Fields": [
                                        { "Id": 1, "Name": "id", "Required": true, "Type": "uuid" },
                                        { "Id": 2, "Name": "subject", "Required": false, "Type": "string" }
                                      ]
                                    },
                                    "Properties": { "write.format.default": "parquet" }
                                  }
                                ]
                              }
                            }
                          }
                        }
                        """.formatted(databaseName, tableName))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTable")
                .body("{ \"DatabaseName\": \"%s\", \"Name\": \"%s\" }".formatted(databaseName, tableName))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Table.VersionId", equalTo("1"))
                .body("Table.Parameters.table_type", equalTo("ICEBERG"))
                .body("Table.Parameters.'write.format.default'", equalTo("parquet"))
                .body("Table.StorageDescriptor.Columns.size()", equalTo(2))
                .body("Table.StorageDescriptor.Columns.Name", hasItems("id", "subject"))
                .body("Table.StorageDescriptor.Columns.find { it.Name == 'subject' }.Type", equalTo("string"));
    }

    @Test
    void updateTableIcebergWithoutNameReturns400() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.UpdateTable")
                .body("""
                        {
                          "DatabaseName": "anything",
                          "UpdateOpenTableFormatInput": {
                            "UpdateIcebergInput": { "UpdateIcebergTableInput": { "Updates": [] } }
                          }
                        }
                        """)
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("InvalidInputException"));
    }

    @Test
    void updateTableIcebergWithConflictingNamesReturns400() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.UpdateTable")
                .body("""
                        {
                          "DatabaseName": "anything",
                          "Name": "table_a",
                          "TableInput": { "Name": "table_b" },
                          "UpdateOpenTableFormatInput": {
                            "UpdateIcebergInput": { "UpdateIcebergTableInput": { "Updates": [] } }
                          }
                        }
                        """)
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("InvalidInputException"));
    }
}
