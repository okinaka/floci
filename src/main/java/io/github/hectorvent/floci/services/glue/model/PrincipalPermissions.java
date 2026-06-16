package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class PrincipalPermissions {
    @JsonProperty("Principal")
    private DataLakePrincipal principal;
    @JsonProperty("Permissions")
    private List<String> permissions;

    public PrincipalPermissions() {}

    public DataLakePrincipal getPrincipal() { return principal; }
    public void setPrincipal(DataLakePrincipal principal) { this.principal = principal; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }

    @RegisterForReflection
    public static class DataLakePrincipal {
        @JsonProperty("DataLakePrincipalIdentifier")
        private String dataLakePrincipalIdentifier;

        public DataLakePrincipal() {}

        public String getDataLakePrincipalIdentifier() { return dataLakePrincipalIdentifier; }
        public void setDataLakePrincipalIdentifier(String dataLakePrincipalIdentifier) {
            this.dataLakePrincipalIdentifier = dataLakePrincipalIdentifier;
        }
    }
}
