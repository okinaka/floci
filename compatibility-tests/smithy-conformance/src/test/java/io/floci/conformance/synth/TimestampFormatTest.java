package io.floci.conformance.synth;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.util.SmithyModelLoader;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import static org.assertj.core.api.Assertions.assertThat;
class TimestampFormatTest {
  static final Model S3 = SmithyModelLoader.loadS3();
  static StructureShape input(String op){
    return S3.expectShape(S3.expectShape(ShapeId.from("com.amazonaws.s3#"+op)).asOperationShape().orElseThrow().getInputShape(), StructureShape.class);
  }
  @Test void retainUntilDateIsIso() {
    var in = new InputSynthesizer(S3, InputSynthesizer.allMembers(), null).synthesizeInput(input("PutObjectRetention"));
    // RetainUntilDate (date-time) must be an ISO-8601 string, not an epoch number
    var ret = in.get("Retention");
    assertThat(ret.get("RetainUntilDate").isTextual()).isTrue();
    assertThat(ret.get("RetainUntilDate").asText()).isEqualTo("2020-01-01T00:00:00Z");
  }
}
