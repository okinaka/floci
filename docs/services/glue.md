# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog and Glue Schema Registry, allowing you to manage local data lake metadata and schema-version workflows.

## Supported Actions

### Data Catalog

| Area | Actions |
|---|---|
| Databases | `CreateDatabase` · `GetDatabase` · `GetDatabases` · `DeleteDatabase` |
| Tables | `CreateTable` · `GetTable` · `GetTables` · `UpdateTable` · `DeleteTable` |
| Partitions | `CreatePartition` · `GetPartitions` |
| User-defined functions | `CreateUserDefinedFunction` · `GetUserDefinedFunction` · `GetUserDefinedFunctions` · `UpdateUserDefinedFunction` · `DeleteUserDefinedFunction` |

### Schema Registry

| Area | Actions |
|---|---|
| Registries | `CreateRegistry` · `GetRegistry` · `ListRegistries` · `UpdateRegistry` · `DeleteRegistry` |
| Schemas | `CreateSchema` · `GetSchema` · `ListSchemas` · `UpdateSchema` · `DeleteSchema` |
| Versions | `RegisterSchemaVersion` · `GetSchemaByDefinition` · `GetSchemaVersion` · `ListSchemaVersions` · `DeleteSchemaVersions` · `GetSchemaVersionsDiff` · `CheckSchemaVersionValidity` |
| Metadata and tags | `PutSchemaVersionMetadata` · `RemoveSchemaVersionMetadata` · `QuerySchemaVersionMetadata` · `TagResource` · `UntagResource` · `GetTags` |

Supported schema formats are `AVRO`, `JSON`, and `PROTOBUF`. Compatibility modes are `NONE`, `DISABLED`, `BACKWARD`, `BACKWARD_ALL`, `FORWARD`, `FORWARD_ALL`, `FULL`, and `FULL_ALL`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_GLUE_ENABLED` | `true` | Enable or disable the service |

## Integration with Athena

The Glue Data Catalog is automatically used by **Athena** to resolve table names to S3 locations and formats. When you submit an Athena query, Floci reads all Glue tables for the target database and generates DuckDB views on top of the underlying S3 objects before executing the SQL.

Tables can reference a Schema Registry schema version through `StorageDescriptor.SchemaReference`. On `GetTable` and `GetTables`, Floci resolves the schema definition into Glue columns when possible.

The DuckDB read function is selected based on the table's `StorageDescriptor.InputFormat` and `StorageDescriptor.SerdeInfo.SerializationLibrary`:

| Condition | DuckDB function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

## Data Catalog Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a database
aws glue create-database \
  --database-input '{"Name": "analytics"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a JSON table (standard AWS format for NDJSON data)
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/orders/",
      "InputFormat": "org.apache.hadoop.mapred.TextInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.openx.data.jsonserde.JsonSerDe"
      },
      "Columns": [
        {"Name": "id",     "Type": "int"},
        {"Name": "amount", "Type": "double"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a Parquet table
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "events",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/events/",
      "InputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      },
      "Columns": [
        {"Name": "event_id", "Type": "string"},
        {"Name": "ts",       "Type": "bigint"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Iceberg Tables (catalog-only)

`CreateTable` accepts an `OpenTableFormatInput` and `UpdateTable` accepts an `UpdateOpenTableFormatInput`, so the Terraform AWS provider's `aws_glue_catalog_table` with an `open_table_format_input.iceberg_input` block (and the AWS SDK Iceberg create/update flows) round-trip cleanly.

This is a **catalog-only** representation:

- Columns are derived from the Iceberg schema fields, and the table is stored with `TableType=EXTERNAL_TABLE`, the Iceberg `InputFormat`/`OutputFormat`/`SerDe`, and the parameters `table_type=ICEBERG`, `format-version`, and `metadata_location`.
- No Iceberg `metadata.json`, manifests, or data files are written to S3 (`metadata_location` is a placeholder path). Reading actual Iceberg data is out of scope.
- `UpdateTable` with `UpdateOpenTableFormatInput` applies schema evolution (re-derives columns), location and property changes, archives the previous version, and bumps `VersionId`. A `TableInput` sent alongside it is applied as a partial overlay (it does not clobber the Iceberg-managed columns/storage descriptor).

Iceberg field types are mapped to Glue (Hive) column types:

| Iceberg type | Glue column type |
|---|---|
| `uuid`, `string`, `time` | `string` |
| `int`, `integer` | `int` |
| `long` | `bigint` |
| `float` / `double` | `float` / `double` |
| `boolean` / `date` / `binary` | `boolean` / `date` / `binary` |
| `timestamp`, `timestamptz` | `timestamp` |
| `decimal(p,s)` | `decimal(p,s)` |
| `list` of `T` | `array<T>` |
| `map` of `K`,`V` | `map<K,V>` |
| `struct` | `struct<...>` |

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws glue create-table \
  --database-name analytics \
  --table-input '{"Name": "email_archive_summaries", "TableType": "EXTERNAL_TABLE"}' \
  --open-table-format-input '{
    "IcebergInput": {
      "MetadataOperation": "CREATE",
      "Version": "2",
      "CreateIcebergTableInput": {
        "Location": "s3://my-bucket/email_archive_summaries",
        "Schema": {
          "Type": "struct",
          "Fields": [
            {"Id": 1, "Name": "id",         "Required": true,  "Type": "uuid"},
            {"Id": 2, "Name": "created_at", "Required": true,  "Type": "timestamptz"},
            {"Id": 3, "Name": "recipients", "Required": false,
             "Type": {"type": "list", "element-id": 10, "element": "string", "element-required": false}}
          ]
        }
      }
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# GetTable returns derived columns (id:string, created_at:timestamp, recipients:array<string>)
# plus parameters table_type=ICEBERG and metadata_location.
aws glue get-table \
  --database-name analytics \
  --name email_archive_summaries \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Schema Registry Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

cat > /tmp/order.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"}]}
JSON

cat > /tmp/order-v2.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"},{"name":"amount","type":["null","double"],"default":null}]}
JSON

aws glue create-registry \
  --registry-name local-registry \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue create-schema \
  --registry-id RegistryName=local-registry \
  --schema-name orders \
  --data-format AVRO \
  --compatibility BACKWARD \
  --schema-definition file:///tmp/order.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue register-schema-version \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --schema-definition file:///tmp/order-v2.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue list-schema-versions \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --endpoint-url $AWS_ENDPOINT_URL
```
