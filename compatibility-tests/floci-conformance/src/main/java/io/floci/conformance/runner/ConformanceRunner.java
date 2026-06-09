package io.floci.conformance.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
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
 * invoke each variant → classify each response into a {@link VariantResult}.
 *
 * <p>Phase A keeps classification deliberately coarse: protocol-level outcomes
 * only (2xx vs 4xx vs 5xx, error-type membership). Field-level shape
 * conformance lives in a later validator phase.
 */
public final class ConformanceRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XmlMapper XML = new XmlMapper();

    private final Model model;
    private final Invoker invoker;
    private final List<Generator> generators;

    public ConformanceRunner(Model model, Invoker invoker, List<Generator> generators) {
        this.model = model;
        this.invoker = invoker;
        this.generators = List.copyOf(generators);
    }

    /**
     * Run every generator across the operations of the given service.
     *
     * @param serviceShapeId Smithy ID of the service shape (e.g.
     *                       {@code com.amazonaws.email#SimpleEmailService}).
     */
    public List<VariantResult> run(String serviceShapeId) {
        Collection<OperationShape> ops = operationsOf(serviceShapeId);
        List<VariantResult> results = new ArrayList<>();
        for (OperationShape op : ops) {
            for (Generator gen : generators) {
                gen.generate(op, model).forEach(v -> results.add(execute(v)));
            }
        }
        return results;
    }

    /**
     * Run every generator across an explicit set of operation names. Useful for
     * scoping to a known-supported subset during early phases.
     */
    public List<VariantResult> runOperations(String serviceShapeId, Set<String> operationNames) {
        Set<OperationShape> all = new HashSet<>(operationsOf(serviceShapeId));
        List<VariantResult> results = new ArrayList<>();
        for (OperationShape op : all) {
            if (!operationNames.contains(op.getId().getName())) {
                continue;
            }
            for (Generator gen : generators) {
                gen.generate(op, model).forEach(v -> results.add(execute(v)));
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

    private VariantResult execute(Variant variant) {
        InvocationResponse resp;
        try {
            resp = invoker.send(variant);
        } catch (IOException e) {
            return new VariantResult(variant, Verdict.HARNESS_ERROR, -1, null,
                    "I/O error: " + e.getMessage());
        }
        return classify(variant, resp);
    }

    private VariantResult classify(Variant variant, InvocationResponse resp) {
        if (resp.is5xx()) {
            return new VariantResult(variant, Verdict.FAIL_5XX, resp.httpStatus(),
                    extractErrorType(resp), truncate(resp.body()));
        }
        if (resp.is4xx()) {
            String errorType = extractErrorType(resp);
            if (errorType == null) {
                return new VariantResult(variant, Verdict.FAIL_4XX_UNROUTED, resp.httpStatus(),
                        null, truncate(resp.body()));
            }
            if (variant.expectedOutcome() == ExpectedOutcome.CLIENT_ERROR) {
                if (matchesExpectedError(variant, errorType)) {
                    return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), errorType, null);
                }
                return new VariantResult(variant, Verdict.FAIL_WRONG_ERROR_TYPE, resp.httpStatus(),
                        errorType, "expected " + variant.expectedError() + ", got " + errorType);
            }
            // SUCCESS-expecting variant got a 4xx; treat as wrong-error if AWS-shaped.
            return new VariantResult(variant, Verdict.FAIL_WRONG_ERROR_TYPE, resp.httpStatus(),
                    errorType, "expected 2xx, got " + errorType);
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

    private static boolean matchesExpectedError(Variant variant, String errorType) {
        if (variant.expectedError() == null) {
            return true;
        }
        // Tolerant compare: errorType may be "Foo", "com.amazonaws.email#Foo", "Sender.Foo", etc.
        String tail = errorType;
        int hash = tail.lastIndexOf('#');
        if (hash >= 0) {
            tail = tail.substring(hash + 1);
        }
        int dot = tail.lastIndexOf('.');
        if (dot >= 0) {
            tail = tail.substring(dot + 1);
        }
        return tail.equals(variant.expectedError());
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
