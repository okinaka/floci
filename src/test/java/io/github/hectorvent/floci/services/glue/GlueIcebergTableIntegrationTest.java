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
class GlueIcebergTableIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createIcebergTable_derivesColumnsAndParametersOnGetTable() {
        String databaseName = "iceberg-db-" + UUID.randomUUID().toString().substring(0, 8);
        String tableName = "email_archive_summaries";
        String location = "s3://dev-base-email-archive-search/email_archive_summaries";

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("""
                        { "DatabaseInput": { "Name": "%s" } }
                        """.formatted(databaseName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateTable")
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableInput": {
                            "Name": "%s",
                            "TableType": "EXTERNAL_TABLE"
                          },
                          "OpenTableFormatInput": {
                            "IcebergInput": {
                              "MetadataOperation": "CREATE",
                              "Version": "2",
                              "CreateIcebergTableInput": {
                                "Location": "%s",
                                "Schema": {
                                  "SchemaId": 0,
                                  "Type": "struct",
                                  "Fields": [
                                    { "Id": 1, "Name": "mail_notification_id", "Required": true, "Type": "uuid" },
                                    { "Id": 2, "Name": "created_at", "Required": true, "Type": "timestamptz" },
                                    { "Id": 4, "Name": "action", "Required": true, "Type": "string" },
                                    { "Id": 5, "Name": "to_address_mail_addresses", "Required": false,
                                      "Type": { "type": "list", "element-id": 10, "element": "string", "element-required": false } }
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """.formatted(databaseName, tableName, location))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTable")
                .body("""
                        {
                          "DatabaseName": "%s",
                          "Name": "%s"
                        }
                        """.formatted(databaseName, tableName))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Table.Name", equalTo(tableName))
                .body("Table.TableType", equalTo("EXTERNAL_TABLE"))
                .body("Table.Parameters.table_type", equalTo("ICEBERG"))
                .body("Table.Parameters.metadata_location", org.hamcrest.Matchers.startsWith(location))
                .body("Table.StorageDescriptor.Location", equalTo(location))
                .body("Table.StorageDescriptor.Columns.size()", equalTo(4))
                .body("Table.StorageDescriptor.Columns.Name",
                        hasItems("mail_notification_id", "created_at", "action", "to_address_mail_addresses"))
                .body("Table.StorageDescriptor.Columns.find { it.Name == 'mail_notification_id' }.Type",
                        equalTo("string"))
                .body("Table.StorageDescriptor.Columns.find { it.Name == 'created_at' }.Type",
                        equalTo("timestamp"))
                .body("Table.StorageDescriptor.Columns.find { it.Name == 'to_address_mail_addresses' }.Type",
                        equalTo("array<string>"));
    }
}
