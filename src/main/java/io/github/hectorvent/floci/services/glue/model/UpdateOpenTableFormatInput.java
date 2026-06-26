package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the AWS Glue {@code UpdateTable} request's {@code UpdateOpenTableFormatInput} field.
 * Only the Iceberg subset that Floci emulates (catalog-only) is modeled. The Iceberg schema
 * shape is reused from {@link OpenTableFormatInput.IcebergSchema}.
 */
@RegisterForReflection
public class UpdateOpenTableFormatInput {
    @JsonProperty("UpdateIcebergInput")
    private UpdateIcebergInput updateIcebergInput;

    public UpdateOpenTableFormatInput() {}

    public UpdateIcebergInput getUpdateIcebergInput() { return updateIcebergInput; }
    public void setUpdateIcebergInput(UpdateIcebergInput updateIcebergInput) {
        this.updateIcebergInput = updateIcebergInput;
    }

    @RegisterForReflection
    public static class UpdateIcebergInput {
        @JsonProperty("UpdateIcebergTableInput")
        private UpdateIcebergTableInput updateIcebergTableInput;

        public UpdateIcebergInput() {}

        public UpdateIcebergTableInput getUpdateIcebergTableInput() { return updateIcebergTableInput; }
        public void setUpdateIcebergTableInput(UpdateIcebergTableInput updateIcebergTableInput) {
            this.updateIcebergTableInput = updateIcebergTableInput;
        }
    }

    @RegisterForReflection
    public static class UpdateIcebergTableInput {
        @JsonProperty("Updates")
        private List<IcebergTableUpdate> updates;

        public UpdateIcebergTableInput() {}

        public List<IcebergTableUpdate> getUpdates() { return updates; }
        public void setUpdates(List<IcebergTableUpdate> updates) { this.updates = updates; }
    }

    @RegisterForReflection
    public static class IcebergTableUpdate {
        @JsonProperty("Schema")
        private OpenTableFormatInput.IcebergSchema schema;
        @JsonProperty("Location")
        private String location;
        @JsonProperty("Properties")
        private Map<String, String> properties;

        public IcebergTableUpdate() {}

        public OpenTableFormatInput.IcebergSchema getSchema() { return schema; }
        public void setSchema(OpenTableFormatInput.IcebergSchema schema) { this.schema = schema; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public Map<String, String> getProperties() { return properties; }
        public void setProperties(Map<String, String> properties) { this.properties = properties; }
    }
}
