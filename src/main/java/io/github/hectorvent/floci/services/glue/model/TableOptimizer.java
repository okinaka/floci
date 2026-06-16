package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Mirrors the AWS Glue {@code TableOptimizer} resource used by the Iceberg table optimizer APIs
 * ({@code CreateTableOptimizer} / {@code GetTableOptimizer} / {@code UpdateTableOptimizer} /
 * {@code DeleteTableOptimizer}). Floci emulates CRUD round-trip only; no compaction is run.
 */
@RegisterForReflection
public class TableOptimizer {
    @JsonProperty("type")
    private String type;
    @JsonProperty("configuration")
    private TableOptimizerConfiguration configuration;

    public TableOptimizer() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public TableOptimizerConfiguration getConfiguration() { return configuration; }
    public void setConfiguration(TableOptimizerConfiguration configuration) { this.configuration = configuration; }

    @RegisterForReflection
    public static class TableOptimizerConfiguration {
        @JsonProperty("roleArn")
        private String roleArn;
        @JsonProperty("enabled")
        private Boolean enabled;

        public TableOptimizerConfiguration() {}

        public String getRoleArn() { return roleArn; }
        public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
