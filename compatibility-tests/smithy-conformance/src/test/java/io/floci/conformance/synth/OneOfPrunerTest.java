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
    private static final Model V1 = SmithyModelLoader.loadSesV1();
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
    void keepsSnsBranchForSesV1EventDestination() {
        // SES v1: SNS is preferred (first) over a synthesized CloudWatch destination.
        OneOfPruner v1Pruner = new OneOfPruner(V1);
        var in = tree("""
                {"ConfigurationSetName":"cs","EventDestination":{
                  "Name":"ed",
                  "CloudWatchDestination":{"DimensionConfigurations":[]},
                  "SNSDestination":{"TopicARN":"arn:aws:sns:us-east-1:1:t"}}}""");
        StructureShape input = V1.expectShape(V1.expectShape(
                ShapeId.from("com.amazonaws.ses#CreateConfigurationSetEventDestination"))
                .asOperationShape().orElseThrow().getInputShape(), StructureShape.class);
        v1Pruner.prune(in, input);
        var ed = in.get("EventDestination");
        assertThat(ed.has("SNSDestination")).isTrue();
        assertThat(ed.has("CloudWatchDestination")).isFalse();
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
