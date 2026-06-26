package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * Mirrors the AWS Glue {@code CreateTable} request's {@code OpenTableFormatInput} field.
 * Only the Iceberg subset that Floci emulates (catalog-only) is modeled.
 */
@RegisterForReflection
public class OpenTableFormatInput {
    @JsonProperty("IcebergInput")
    private IcebergInput icebergInput;

    public OpenTableFormatInput() {}

    public IcebergInput getIcebergInput() { return icebergInput; }
    public void setIcebergInput(IcebergInput icebergInput) { this.icebergInput = icebergInput; }

    @RegisterForReflection
    public static class IcebergInput {
        @JsonProperty("MetadataOperation")
        private String metadataOperation;
        @JsonProperty("Version")
        private String version;
        @JsonProperty("CreateIcebergTableInput")
        private CreateIcebergTableInput createIcebergTableInput;

        public IcebergInput() {}

        public String getMetadataOperation() { return metadataOperation; }
        public void setMetadataOperation(String metadataOperation) { this.metadataOperation = metadataOperation; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public CreateIcebergTableInput getCreateIcebergTableInput() { return createIcebergTableInput; }
        public void setCreateIcebergTableInput(CreateIcebergTableInput createIcebergTableInput) {
            this.createIcebergTableInput = createIcebergTableInput;
        }
    }

    @RegisterForReflection
    public static class CreateIcebergTableInput {
        @JsonProperty("Location")
        private String location;
        @JsonProperty("Schema")
        private IcebergSchema schema;
        @JsonProperty("Properties")
        private java.util.Map<String, String> properties;

        public CreateIcebergTableInput() {}

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public IcebergSchema getSchema() { return schema; }
        public void setSchema(IcebergSchema schema) { this.schema = schema; }
        public java.util.Map<String, String> getProperties() { return properties; }
        public void setProperties(java.util.Map<String, String> properties) { this.properties = properties; }
    }

    @RegisterForReflection
    public static class IcebergSchema {
        @JsonProperty("SchemaId")
        private Integer schemaId;
        @JsonProperty("Type")
        private String type;
        @JsonProperty("Fields")
        private List<IcebergField> fields;

        public IcebergSchema() {}

        public Integer getSchemaId() { return schemaId; }
        public void setSchemaId(Integer schemaId) { this.schemaId = schemaId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<IcebergField> getFields() { return fields; }
        public void setFields(List<IcebergField> fields) { this.fields = fields; }
    }

    @RegisterForReflection
    public static class IcebergField {
        @JsonProperty("Id")
        private Integer id;
        @JsonProperty("Name")
        private String name;
        @JsonProperty("Required")
        private Boolean required;
        // Iceberg field type is a document: either a JSON string ("uuid") or a
        // nested object ({"type":"list", "element":"string", ...}).
        @JsonProperty("Type")
        private Object type;

        public IcebergField() {}

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Boolean getRequired() { return required; }
        public void setRequired(Boolean required) { this.required = required; }
        public Object getType() { return type; }
        public void setType(Object type) { this.type = type; }
    }
}
