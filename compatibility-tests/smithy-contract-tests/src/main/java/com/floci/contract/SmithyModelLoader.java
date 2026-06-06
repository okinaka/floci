package com.floci.contract;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidatedResult;

import java.net.URL;
import java.util.Objects;

/**
 * Loads an AWS Smithy 2.0 IDL JSON service model from a classpath resource.
 * The model files in this module's {@code src/main/resources/models/} directory are
 * verbatim copies from <a href="https://github.com/aws/api-models-aws">aws/api-models-aws</a>.
 *
 * <p>The AWS service models reference traits from {@code smithy-aws-traits} and
 * {@code smithy-rules-engine} (endpoint rules, sigv4 auth, restJson1 protocol, ...).
 * Some of those traits target bleeding-edge versions that may not be resolvable by
 * the current Smithy artifact set on the test classpath. Since the contract
 * validator here only walks Structure / Member / List / Map / scalar shapes — not
 * service endpoint rules or auth — we accept a {@link ValidatedResult} that still
 * contains unresolved-trait errors as long as a {@link Model} could be assembled.
 */
public final class SmithyModelLoader {

    private SmithyModelLoader() {}

    /**
     * @param classpathResource e.g. {@code "models/sesv2.json"}
     */
    public static Model load(String classpathResource) {
        URL url = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResource(classpathResource),
                "Smithy model not on classpath: " + classpathResource);
        ValidatedResult<Model> result = Model.assembler()
                .addImport(url)
                .assemble();
        return result.getResult().orElseThrow(() -> new IllegalStateException(
                "Could not assemble Smithy model " + classpathResource + ":\n" + result.getValidationEvents()));
    }
}
