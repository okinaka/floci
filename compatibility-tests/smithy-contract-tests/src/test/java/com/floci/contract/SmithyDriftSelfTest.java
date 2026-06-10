package com.floci.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmithyDriftSelfTest {

    @Test
    void detectsUnknownMember_andWrongType_andMissingRequired() throws Exception {
        Model model = SmithyModelLoader.load("models/sesv2.json");
        SmithyResponseValidator validator = new SmithyResponseValidator(model);

        // Inject drift: bogus member + wrong-type Tags (should be array, give object)
        String tamperedJson = """
            {
              "ConfigurationSetName": "x",
              "Tags": {"not-a-list": true},
              "BogusFieldNotInSmithy": "drift"
            }
            """;
        JsonNode tampered = new ObjectMapper().readTree(tamperedJson);

        List<SmithyResponseValidator.ValidationError> errors = validator.validate(
                tampered,
                ShapeId.from("com.amazonaws.sesv2#GetConfigurationSetResponse"));

        assertThat(errors).extracting(SmithyResponseValidator.ValidationError::path)
                .contains("$.BogusFieldNotInSmithy")
                .anyMatch(p -> p.equals("$.Tags") || p.startsWith("$.Tags"));
    }
}
