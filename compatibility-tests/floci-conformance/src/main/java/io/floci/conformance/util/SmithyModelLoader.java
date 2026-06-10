package io.floci.conformance.util;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;

import java.net.URL;

/**
 * Loads Smithy models from the JAR's {@code resources/models/} directory.
 *
 * <p>Uses {@code result.getResult().orElseThrow()} rather than {@code unwrap()}
 * so that AWS service models with bleeding-edge traits (which fail strict
 * validation but parse fine) still load. The harness only cares about
 * structural shape data, not trait soundness.
 */
public final class SmithyModelLoader {

    private SmithyModelLoader() {
    }

    /** SES v1 (Query / XML protocol). */
    public static Model loadSesV1() {
        return loadFromClasspath("models/ses.json");
    }

    /** SES v2 (REST JSON protocol). */
    public static Model loadSesV2() {
        return loadFromClasspath("models/sesv2.json");
    }

    /** S3 (REST XML protocol). */
    public static Model loadS3() {
        return loadFromClasspath("models/s3.json");
    }

    /** SSM (AWS JSON 1.1 protocol). */
    public static Model loadSsm() {
        return loadFromClasspath("models/ssm.json");
    }

    public static Model loadFromClasspath(String resourcePath) {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("Smithy model not found on classpath: " + resourcePath);
        }
        return new ModelAssembler()
                .addImport(url)
                .assemble()
                .getResult()
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to load Smithy model: " + resourcePath));
    }
}
