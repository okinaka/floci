package io.floci.conformance.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.conformance.classify.ErrorClassifier;
import io.floci.conformance.classify.ErrorClassifier.Category;
import io.floci.conformance.encode.RequestEncoder;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.generator.Generator;
import io.floci.conformance.invoke.InvocationResponse;
import io.floci.conformance.invoke.Invoker;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.model.VariantResult;
import io.floci.conformance.model.Verdict;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates: pick operations from the model → fan generators across them →
 * encode each case via the protocol-specific {@link RequestEncoder} → invoke
 * → classify each response into a {@link VariantResult}.
 *
 * <p>Phase A/B keep classification at the protocol layer (2xx vs 4xx vs 5xx,
 * error-type membership). Body-shape conformance lives in a later validator.
 */
public final class ConformanceRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XmlMapper XML = new XmlMapper();

    private final Model model;
    private final Invoker invoker;
    private final RequestEncoder encoder;
    private final List<Generator> generators;
    private final ErrorClassifier errorClassifier;

    public ConformanceRunner(Model model, Invoker invoker, RequestEncoder encoder,
                             List<Generator> generators) {
        this.model = model;
        this.invoker = invoker;
        this.encoder = encoder;
        this.generators = List.copyOf(generators);
        this.errorClassifier = new ErrorClassifier();
    }

    public List<VariantResult> run(String serviceShapeId) {
        Collection<OperationShape> ops = operationsOf(serviceShapeId);
        List<VariantResult> results = new ArrayList<>();
        for (OperationShape op : ops) {
            for (Generator gen : generators) {
                gen.generate(op, model).forEach(c -> results.add(execute(c)));
            }
        }
        return results;
    }

    public List<VariantResult> runOperations(String serviceShapeId, Set<String> operationNames) {
        Set<OperationShape> all = new HashSet<>(operationsOf(serviceShapeId));
        List<VariantResult> results = new ArrayList<>();
        for (OperationShape op : all) {
            if (!operationNames.contains(op.getId().getName())) {
                continue;
            }
            for (Generator gen : generators) {
                gen.generate(op, model).forEach(c -> results.add(execute(c)));
            }
        }
        return results;
    }

    private Collection<OperationShape> operationsOf(String serviceShapeId) {
        ShapeId id = ShapeId.from(serviceShapeId);
        var service = model.expectShape(id).asServiceShape().orElseThrow(() ->
                new IllegalArgumentException("Not a service shape: " + serviceShapeId));
        Set<OperationShape> ops = new LinkedHashSet<>();
        service.getAllOperations().forEach(opId -> ops.add(model.expectShape(opId, OperationShape.class)));
        return ops;
    }

    private VariantResult execute(GeneratedCase generated) {
        Variant variant;
        try {
            variant = encoder.encode(generated);
        } catch (RuntimeException e) {
            Variant ph = placeholderVariant(generated);
            return new VariantResult(ph, Verdict.HARNESS_ERROR, -1, null,
                    "encoder error: " + e.getMessage());
        }
        InvocationResponse resp;
        try {
            resp = invoker.send(variant);
        } catch (IOException e) {
            return new VariantResult(variant, Verdict.HARNESS_ERROR, -1, null,
                    "I/O error: " + e.getMessage());
        }
        return classify(variant, resp);
    }

    private static Variant placeholderVariant(GeneratedCase g) {
        return new Variant(g.operation(), g.generator(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, g.expectedOutcome(), g.expectedError());
    }

    private VariantResult classify(Variant variant, InvocationResponse resp) {
        if (resp.is5xx()) {
            return new VariantResult(variant, Verdict.FAIL_5XX, resp.httpStatus(),
                    extractErrorType(resp), truncate(resp.body()));
        }
        if (resp.is4xx()) {
            String rawType = extractErrorType(resp);
            if (rawType == null) {
                return new VariantResult(variant, Verdict.FAIL_4XX_UNROUTED, resp.httpStatus(),
                        null, truncate(resp.body()));
            }
            String normalized = ErrorClassifier.normalize(rawType);
            Category category = errorClassifier.classify(variant.operation(), rawType);
            boolean negative = variant.expectedOutcome() == ExpectedOutcome.CLIENT_ERROR;

            if (category == Category.NOT_IMPLEMENTED) {
                return new VariantResult(variant, Verdict.NOT_IMPLEMENTED, resp.httpStatus(),
                        rawType, "operation not implemented (" + normalized + ")");
            }

            if (negative) {
                if (variant.expectedError() != null
                        && variant.expectedError().equals(normalized)) {
                    return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), rawType, null);
                }
                return switch (category) {
                    case VALIDATION, DECLARED_BY_OP -> new VariantResult(
                            variant, Verdict.PASS, resp.httpStatus(), rawType, null);
                    case STATE -> new VariantResult(
                            variant, Verdict.INCONCLUSIVE_STATE, resp.httpStatus(), rawType,
                            "state collision masked negative test (" + normalized + ")");
                    default -> new VariantResult(
                            variant, Verdict.FAIL_WRONG_ERROR_TYPE, resp.httpStatus(), rawType,
                            "expected validation error, got " + normalized);
                };
            }

            // SUCCESS expected but got 4xx.
            return switch (category) {
                case VALIDATION, DECLARED_BY_OP -> new VariantResult(
                        variant, Verdict.INCONCLUSIVE_VALIDATION, resp.httpStatus(), rawType,
                        "harness input rejected (" + normalized + ")");
                case STATE -> new VariantResult(
                        variant, Verdict.INCONCLUSIVE_STATE, resp.httpStatus(), rawType,
                        "state collision (" + normalized + ")");
                default -> new VariantResult(
                        variant, Verdict.FAIL_WRONG_ERROR_TYPE, resp.httpStatus(), rawType,
                        "expected 2xx, got " + normalized);
            };
        }
        if (resp.is2xx()) {
            if (variant.expectedOutcome() == ExpectedOutcome.CLIENT_ERROR) {
                return new VariantResult(variant, Verdict.FAIL_SILENT_PASS, resp.httpStatus(),
                        null, "expected error but got 2xx");
            }
            return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), null, null);
        }
        return new VariantResult(variant, Verdict.HARNESS_ERROR, resp.httpStatus(),
                null, "unexpected status " + resp.httpStatus());
    }

    private static String extractErrorType(InvocationResponse resp) {
        if (resp.body() == null || resp.body().isEmpty()) {
            return null;
        }
        String ct = resp.contentType() == null ? "" : resp.contentType().toLowerCase();
        try {
            if (ct.contains("xml") || resp.body().startsWith("<")) {
                JsonNode root = XML.readTree(resp.body().getBytes());
                JsonNode code = root.findValue("Code");
                return code != null && code.isTextual() ? code.asText() : null;
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode t = root.get("__type");
            if (t == null) {
                t = root.get("code");
            }
            return t != null && t.isTextual() ? t.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
