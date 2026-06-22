package io.floci.conformance.synth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

import static org.assertj.core.api.Assertions.assertThat;

class OneOfPrunerTest {

    private static final Model V2 = SmithyModelLoader.loadSesV2();
    private static final OneOfPruner PRUNER = new OneOfPruner(V2);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static StructureShape input(String op) {
        return V2.expectShape(V2.expectShape(
                ShapeId.from("com.amazonaws.sesv2#" + op)).asOperationShape().orElseThrow()
                .getInputShape(), StructureShape.class);
    }

    @Test
    void keepsOnlyFirstEventDestinationBranch() {
        var in = tree("""
                {"ConfigurationSetName":"cs","EventDestinationName":"ed","EventDestination":{
                  "Enabled":true,
                  "KinesisFirehoseDestination":{"IamRoleArn":"r"},
                  "CloudWatchDestination":{"DimensionConfigurations":[]},
                  "SnsDestination":{"TopicArn":"t"}}}""");
        PRUNER.prune(in, input("CreateConfigurationSetEventDestination"));
        var ed = in.get("EventDestination");
        assertThat(ed.has("KinesisFirehoseDestination")).isTrue();   // first kept
        assertThat(ed.has("CloudWatchDestination")).isFalse();
        assertThat(ed.has("SnsDestination")).isFalse();
        assertThat(ed.get("Enabled").asBoolean()).isTrue();          // non-group member untouched
    }

    @Test
    void leavesSingleBranchUntouched() {
        var in = tree("""
                {"ConfigurationSetName":"cs","EventDestinationName":"ed","EventDestination":{
                  "Enabled":true,"SnsDestination":{"TopicArn":"t"}}}""");
        PRUNER.prune(in, input("CreateConfigurationSetEventDestination"));
        assertThat(in.get("EventDestination").has("SnsDestination")).isTrue();
    }

    @Test
    void leavesUnlistedStructuresUntouched() {
        // EmailContent is intentionally NOT pruned; all branches must survive.
        var in = tree("""
                {"FromEmailAddress":"a@b.com","Content":{
                  "Simple":{"Subject":{"Data":"s"}},"Raw":{"Data":"x"}}}""");
        PRUNER.prune(in, input("SendEmail"));
        var content = in.get("Content");
        assertThat(content.has("Simple")).isTrue();
        assertThat(content.has("Raw")).isTrue();
    }
}
