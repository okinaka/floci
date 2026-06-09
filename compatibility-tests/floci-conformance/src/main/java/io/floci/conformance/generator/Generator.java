package io.floci.conformance.generator;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;

import java.util.stream.Stream;

/**
 * One strategy for producing {@link GeneratedCase}s from a Smithy operation.
 *
 * <p>fakecloud-style: each generator captures one orthogonal axis of input
 * variation (boundaries, enum exhaustion, optionals, negatives, etc.).
 * Generators are stateless, protocol-agnostic, and reusable across services.
 */
public interface Generator {

    /**
     * Short, stable name used in baseline keys and reports. Conventionally
     * {@code "category.subkind"}, e.g. {@code "empty.passthrough"} or
     * {@code "optionals.required-only"}.
     */
    String name();

    /**
     * Produce zero or more cases for the given operation. May return an empty
     * stream when the strategy doesn't apply (e.g. enum exhaustion on an
     * operation with no enum members).
     */
    Stream<GeneratedCase> generate(OperationShape op, Model model);
}
