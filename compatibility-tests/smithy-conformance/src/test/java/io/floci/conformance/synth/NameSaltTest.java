package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The salt gives each case its own resource names, except for members naming a
 * resource the service allows only one of per account.
 */
class NameSaltTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ordinaryNamesDifferPerCase() {
        NameSalt.startRun();
        String a = NameSalt.apply(tree("""
                {"TenantName":"cov-probe-x"}"""), "optionals.required-only")
                .get("TenantName").asText();
        String b = NameSalt.apply(tree("""
                {"TenantName":"cov-probe-x"}"""), "optionals.all-members")
                .get("TenantName").asText();
        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("cov-probe-" + NameSalt.nonce());
    }

    @Test
    void accountSingletonNamesAreSharedAcrossCases() {
        // SES allows one contact list per account, so a per-case name would mean
        // only the first case to create one ever finds it.
        NameSalt.startRun();
        String a = NameSalt.apply(tree("""
                {"ContactListName":"cov-probe-x"}"""), "optionals.required-only")
                .get("ContactListName").asText();
        String b = NameSalt.apply(tree("""
                {"ContactListName":"cov-probe-x"}"""), "property-based.seed-3")
                .get("ContactListName").asText();
        assertThat(a).isEqualTo(b);
        assertThat(a).isEqualTo("cov-probe-" + NameSalt.nonce() + "-x");
    }

    @Test
    void theSingletonCarveOutDoesNotLeakToSiblings() {
        NameSalt.startRun();
        JsonNode salted = NameSalt.apply(tree("""
                {"ContactListName":"cov-probe-x","EmailAddress":"cov-probe@example.com",
                 "TopicName":"cov-probe-t"}"""), "optionals.all-members");
        String shared = "cov-probe-" + NameSalt.nonce() + "-x";
        assertThat(salted.get("ContactListName").asText()).isEqualTo(shared);
        // Siblings keep the per-case component, so they still differ between cases.
        assertThat(salted.get("TopicName").asText()).contains(NameSalt.nonce());
        assertThat(salted.get("TopicName").asText()).isNotEqualTo("cov-probe-" + NameSalt.nonce() + "-t");
        assertThat(salted.get("EmailAddress").asText()).contains("@example.com");
    }

    @Test
    void nestedSingletonMembersAreRewrittenToo() {
        NameSalt.startRun();
        JsonNode salted = NameSalt.apply(tree("""
                {"Details":{"ContactListName":"cov-probe-x"},
                 "Items":[{"ContactListName":"cov-probe-x"}]}"""), "optionals.required-only");
        String shared = "cov-probe-" + NameSalt.nonce() + "-x";
        assertThat(salted.get("Details").get("ContactListName").asText()).isEqualTo(shared);
        assertThat(salted.get("Items").get(0).get("ContactListName").asText()).isEqualTo(shared);
    }
}
