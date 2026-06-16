package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class GlueDatabaseDefaultPermissionsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createDatabaseWithDefaultPermissions_roundTripsThroughGetDatabase() {
        String databaseName = "perm-db-" + UUID.randomUUID().toString().substring(0, 8);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("""
                        {
                          "DatabaseInput": {
                            "Name": "%s",
                            "CreateTableDefaultPermissions": [
                              {
                                "Principal": {
                                  "DataLakePrincipalIdentifier": "IAM_ALLOWED_PRINCIPALS"
                                },
                                "Permissions": ["ALL"]
                              }
                            ]
                          }
                        }
                        """.formatted(databaseName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetDatabase")
                .body("""
                        {
                          "Name": "%s"
                        }
                        """.formatted(databaseName))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Database.Name", equalTo(databaseName))
                .body("Database.CreateTableDefaultPermissions.size()", equalTo(1))
                .body("Database.CreateTableDefaultPermissions[0].Principal.DataLakePrincipalIdentifier",
                        equalTo("IAM_ALLOWED_PRINCIPALS"))
                .body("Database.CreateTableDefaultPermissions[0].Permissions", hasItem("ALL"));
    }
}
