package io.floci.conformance.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.conformance.classify.ErrorClassifier;
import io.floci.conformance.classify.ErrorClassifier.Category;
import io.floci.conformance.encode.RequestEncoder;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.generator.Generator;
import io.floci.conformance.scenario.LifecycleScenario;
import io.floci.conformance.scenario.ListAfterCreateGenerator;
import io.floci.conformance.scenario.ListAfterCreateScenario;
import io.floci.conformance.scenario.ReadAfterDeleteGenerator;
import io.floci.conformance.scenario.RoundTripEchoGenerator;
import io.floci.conformance.scenario.RoundTripScenario;
import io.floci.conformance.scenario.SeedAndReadGenerator;
import io.floci.conformance.synth.NameSalt;
import io.floci.conformance.validate.ShapeValidator;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates: pick operations from the model → fan generators across them →
 * encode each case via the protocol-specific {@link RequestEncoder} → invoke
 * → classify each response into a {@link VariantResult}.
 *
 * <p>Per-variant classification covers the protocol layer (2xx vs 4xx vs 5xx,
 * error-type membership) plus body-shape validation of 2xx responses; the
 * two-step scenarios additionally assert write-read echo consistency.
 */
public final class ConformanceRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XmlMapper XML = new XmlMapper();

    /** Protocols whose response bodies are XML and need XML-aware shape walking. */
    private static final Set<String> XML_PROTOCOLS = Set.of(
            "aws.protocols#awsQuery", "aws.protocols#restXml");

    private final Model model;
    private final Invoker invoker;
    private final RequestEncoder encoder;
    private final List<Generator> generators;
    private final ErrorClassifier errorClassifier;
    private final ShapeValidator shapeValidator;
    private final boolean xmlMode;
    private final DependencySeeder seeder;
    private final io.floci.conformance.synth.OneOfPruner oneOfPruner;

    /** Operations referenced by seeding, resolved lazily by name from the model. */
    private final java.util.Map<String, OperationShape> seedOpCache = new java.util.HashMap<>();
    /** Dependency values already seeded this run; reset on each {@link NameSalt#startRun()}. */
    private final Set<String> seededKeys = new java.util.HashSet<>();

    public ConformanceRunner(Model model, Invoker invoker, RequestEncoder encoder,
                             List<Generator> generators) {
        this(model, invoker, encoder, generators, DependencySeeder.NONE);
    }

    public ConformanceRunner(Model model, Invoker invoker, RequestEncoder encoder,
                             List<Generator> generators, DependencySeeder seeder) {
        this.model = model;
        this.invoker = invoker;
        this.encoder = encoder;
        this.generators = List.copyOf(generators);
        this.errorClassifier = new ErrorClassifier();
        this.xmlMode = XML_PROTOCOLS.contains(encoder.protocol());
        this.shapeValidator = new ShapeValidator(model, xmlMode);
        this.seeder = seeder == null ? DependencySeeder.NONE : seeder;
        this.oneOfPruner = new io.floci.conformance.synth.OneOfPruner(model);
    }

    public List<VariantResult> run(String serviceShapeId) {
        return runFiltered(serviceShapeId, op -> true);
    }

    /**
     * Execute {@link RoundTripScenario}s discovered on the service: for each
     * Create-Get pair, write synthetic data then read it back and assert
     * field-level echo. Returns one {@link VariantResult} per scenario.
     */
    public List<VariantResult> runRoundTrip(String serviceShapeId) {
        NameSalt.startRun();
        seededKeys.clear();
        ServiceShape svc = model.expectShape(ShapeId.from(serviceShapeId), ServiceShape.class);
        List<RoundTripScenario> scenarios = new ArrayList<>();
        scenarios.addAll(new RoundTripEchoGenerator().generate(svc, model));
        scenarios.addAll(new SeedAndReadGenerator().generate(svc, model));
        List<VariantResult> results = new ArrayList<>();
        for (RoundTripScenario s : scenarios) {
            results.add(executeScenario(s));
        }
        for (LifecycleScenario s : new ReadAfterDeleteGenerator().generate(svc, model)) {
            results.add(executeReadAfterDelete(s));
        }
        for (ListAfterCreateScenario s : new ListAfterCreateGenerator().generate(svc, model)) {
            results.add(executeListAfterCreate(s));
        }
        return results;
    }

    public List<VariantResult> runOperations(String serviceShapeId, Set<String> operationNames) {
        return runFiltered(serviceShapeId, op -> operationNames.contains(op.getId().getName()));
    }

    /**
     * Write-verb prefixes executed first so later read probes find seeded
     * state. FormatHints derives identifier values deterministically from
     * member names, so a {@code GetX} probe naturally references whatever the
     * earlier {@code CreateX} probe wrote.
     */
    private static final List<String> SETUP_VERBS = List.of(
            "Create", "Put", "Add", "Verify", "Set", "Tag", "Update");
    /** Destructive verbs executed last so they can't erase state reads depend on. */
    private static final List<String> TEARDOWN_VERBS = List.of(
            "Delete", "Remove", "Untag");

    private static int phaseOf(OperationShape op) {
        String name = op.getId().getName();
        for (String v : SETUP_VERBS) {
            if (name.startsWith(v)) {
                return 0;
            }
        }
        for (String v : TEARDOWN_VERBS) {
            if (name.startsWith(v)) {
                return 2;
            }
        }
        return 1;
    }

    private List<VariantResult> runFiltered(String serviceShapeId,
                                            java.util.function.Predicate<OperationShape> filter) {
        NameSalt.startRun();
        seededKeys.clear();
        List<OperationShape> ordered = new ArrayList<>(operationsOf(serviceShapeId));
        // Stable sort: writes → reads/actions → deletes, model order within a phase.
        ordered.sort(java.util.Comparator.comparingInt(ConformanceRunner::phaseOf));
        List<VariantResult> results = new ArrayList<>();
        for (OperationShape op : ordered) {
            if (!filter.test(op)) {
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
        pruneOneOf(generated);
        generated = saltInput(generated);
        seedDependencies(generated.logicalInput());
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

    /**
     * Reduce mutually-exclusive ("exactly one of") member groups in the input to
     * a single branch, so an {@code all-members} input isn't rejected for setting
     * more than one. No-op for inputs with no known one-of groups. Mutates the
     * case's input tree in place (each generator produces a fresh tree per case).
     */
    private void pruneOneOf(GeneratedCase c) {
        Shape input = model.expectShape(c.operation().getInputShape());
        if (input instanceof StructureShape struct) {
            oneOfPruner.prune(c.logicalInput(), struct);
        }
    }

    /**
     * Rewrite synthetic identifiers in the case input so each case (and each
     * run) uses its own resource namespace; see {@link NameSalt}.
     */
    private static GeneratedCase saltInput(GeneratedCase c) {
        JsonNode salted = NameSalt.apply(c.logicalInput(), c.generator());
        if (salted == c.logicalInput()) {
            return c;
        }
        return new GeneratedCase(c.operation(), c.generator(), salted,
                c.expectedOutcome(), c.expectedError());
    }

    /**
     * Pre-create any resources {@code input} references but doesn't create, so
     * the operation under test reaches its real logic instead of failing on an
     * unseeded dependency. Best-effort: the value is already salted, so each
     * seed is sent verbatim (never re-salted), deduplicated per run, and any
     * failure (already-exists, unsupported op) is ignored — the case itself
     * still records the authoritative verdict.
     */
    private void seedDependencies(JsonNode input) {
        if (seeder == DependencySeeder.NONE) {
            return;
        }
        for (DependencySeeder.Seed seed : seeder.seedsFor(input)) {
            String key = seed.operation() + '|' + seed.value();
            if (!seededKeys.add(key)) {
                continue;
            }
            OperationShape op = resolveSeedOperation(seed.operation());
            if (op == null) {
                continue;
            }
            GeneratedCase seedCase = new GeneratedCase(
                    op, "dependency-seed", seedInputNode(seed.inputMember(), seed.value()),
                    ExpectedOutcome.SUCCESS, null);
            try {
                invoker.send(encoder.encode(seedCase));
            } catch (IOException | RuntimeException ignored) {
                // Seeding is best-effort; the case under test still reports the real verdict.
            }
        }
    }

    private OperationShape resolveSeedOperation(String name) {
        return seedOpCache.computeIfAbsent(name, n -> model.getOperationShapes().stream()
                .filter(o -> o.getId().getName().equals(n))
                .findFirst()
                .orElse(null));
    }

    /**
     * Build a seed operation's input tree. A plain member name yields a flat
     * {@code {member: value}}; a dotted path (e.g. {@code ConfigurationSet.Name})
     * yields the nested object the create operation expects.
     */
    private static com.fasterxml.jackson.databind.node.ObjectNode seedInputNode(
            String memberPath, String value) {
        com.fasterxml.jackson.databind.node.ObjectNode root = JSON.createObjectNode();
        String[] path = memberPath.split("\\.");
        com.fasterxml.jackson.databind.node.ObjectNode cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            cur = cur.putObject(path[i]);
        }
        cur.put(path[path.length - 1], value);
        return root;
    }

    /** Outcome of one scenario step: either a terminal result or a 2xx response to continue with. */
    private record StepOutcome(Variant variant, InvocationResponse resp, VariantResult terminal) {
    }

    private StepOutcome sendStep(GeneratedCase stepCase, String phase) {
        Variant variant;
        try {
            variant = encoder.encode(stepCase);
        } catch (RuntimeException e) {
            return new StepOutcome(null, null, new VariantResult(
                    placeholderVariant(stepCase), Verdict.HARNESS_ERROR, -1,
                    null, phase + " encode error: " + e.getMessage()));
        }
        InvocationResponse resp;
        try {
            resp = invoker.send(variant);
        } catch (IOException e) {
            return new StepOutcome(null, null, new VariantResult(
                    variant, Verdict.HARNESS_ERROR, -1, null,
                    phase + " I/O error: " + e.getMessage()));
        }
        if (!resp.is2xx()) {
            return new StepOutcome(null, null, classify(variant, resp));
        }
        return new StepOutcome(variant, resp, null);
    }

    /**
     * Create → delete → read-back lifecycle check. A read that still succeeds
     * after the delete is a definite ghost-record bug; a not-found read is the
     * invariant holding (PASS). Setup steps that can't run leave the case
     * inconclusive rather than asserting anything.
     */
    private VariantResult executeReadAfterDelete(LifecycleScenario s) {
        JsonNode createInput = NameSalt.apply(s.createInput(), s.label());
        JsonNode deleteInput = NameSalt.apply(s.deleteInput(), s.label());
        JsonNode readInput = NameSalt.apply(s.readInput(), s.label());
        seedDependencies(createInput);

        StepOutcome create = sendStep(new GeneratedCase(
                s.createOp(), s.label(), createInput, ExpectedOutcome.SUCCESS, null), "create");
        if (create.terminal() != null) {
            return create.terminal();
        }
        StepOutcome del = sendStep(new GeneratedCase(
                s.deleteOp(), s.label(), deleteInput, ExpectedOutcome.SUCCESS, null), "delete");
        if (del.terminal() != null) {
            return del.terminal();
        }

        // Read-back: send directly so a 4xx (the desired not-found) isn't terminalized.
        GeneratedCase readCase = new GeneratedCase(
                s.readOp(), s.label(), readInput, ExpectedOutcome.CLIENT_ERROR, null);
        Variant rv;
        try {
            rv = encoder.encode(readCase);
        } catch (RuntimeException e) {
            return new VariantResult(placeholderVariant(readCase), Verdict.HARNESS_ERROR, -1,
                    null, "read encode error: " + e.getMessage());
        }
        InvocationResponse rr;
        try {
            rr = invoker.send(rv);
        } catch (IOException e) {
            return new VariantResult(rv, Verdict.HARNESS_ERROR, -1, null,
                    "read I/O error: " + e.getMessage());
        }

        if (rr.is2xx()) {
            return new VariantResult(rv, Verdict.FAIL_DELETED_STILL_READABLE, rr.httpStatus(),
                    null, "resource still readable after delete");
        }
        if (rr.is5xx()) {
            return new VariantResult(rv, Verdict.FAIL_5XX, rr.httpStatus(),
                    extractErrorType(rr), truncate(rr.body()));
        }
        String rawType = extractErrorType(rr);
        if (rawType == null) {
            return new VariantResult(rv, Verdict.FAIL_4XX_UNROUTED, rr.httpStatus(),
                    null, truncate(rr.body()));
        }
        Category cat = errorClassifier.classify(s.readOp(), rr.httpStatus(), rawType);
        String normalized = ErrorClassifier.normalize(rawType);
        return switch (cat) {
            case MISSING -> new VariantResult(rv, Verdict.PASS, rr.httpStatus(), rawType, null);
            case NOT_IMPLEMENTED -> new VariantResult(rv, Verdict.NOT_IMPLEMENTED, rr.httpStatus(),
                    rawType, "operation not implemented (" + normalized + ")");
            case STATE -> new VariantResult(rv, Verdict.INCONCLUSIVE_STATE, rr.httpStatus(),
                    rawType, "state collision (" + normalized + ")");
            default -> new VariantResult(rv, Verdict.INCONCLUSIVE_VALIDATION, rr.httpStatus(),
                    rawType, "read rejected, not a not-found (" + normalized + ")");
        };
    }

    /**
     * Create → list membership check. After creating a resource, the matching
     * List response must contain the new resource's identifier; if it doesn't,
     * that's a definite state bug. Setup that can't run stays inconclusive.
     */
    private VariantResult executeListAfterCreate(ListAfterCreateScenario s) {
        JsonNode createInput = NameSalt.apply(s.createInput(), s.label());
        JsonNode listInput = NameSalt.apply(s.listInput(), s.label());
        seedDependencies(createInput);
        String idValue = createInput.path(s.identifierMember()).asText(null);

        StepOutcome create = sendStep(new GeneratedCase(
                s.createOp(), s.label(), createInput, ExpectedOutcome.SUCCESS, null), "create");
        if (create.terminal() != null) {
            return create.terminal();
        }
        StepOutcome list = sendStep(new GeneratedCase(
                s.listOp(), s.label(), listInput, ExpectedOutcome.SUCCESS, null), "list");
        if (list.terminal() != null) {
            return list.terminal();
        }
        String body = list.resp().body();
        boolean listed = idValue != null && !idValue.isEmpty()
                && body != null && body.contains(idValue);
        if (listed) {
            return new VariantResult(list.variant(), Verdict.PASS, list.resp().httpStatus(), null, null);
        }
        return new VariantResult(list.variant(), Verdict.FAIL_CREATED_NOT_LISTED,
                list.resp().httpStatus(), null,
                "created " + s.identifierMember() + "=" + idValue + " not present in list response");
    }

    private VariantResult executeScenario(RoundTripScenario s) {
        // Salt setup and verify with the one scenario label so the seeded
        // identifier matches on both steps; use the salted setup input for the
        // echo comparison below, not the raw one.
        JsonNode setupInput = NameSalt.apply(s.setupInput(), s.generatorName());
        JsonNode verifyInput = NameSalt.apply(s.verifyInput(), s.generatorName());
        seedDependencies(setupInput);
        seedDependencies(verifyInput);

        // Step 1 — setup. Failure here means we can't tell if the verify step
        // would have round-tripped; report whatever the setup yielded so the
        // user knows it never reached the assertion.
        StepOutcome setup = sendStep(new GeneratedCase(
                s.setupOp(), s.generatorName(), setupInput, ExpectedOutcome.SUCCESS, null),
                "setup");
        if (setup.terminal() != null) {
            return setup.terminal();
        }

        // Step 2 — verify.
        StepOutcome verify = sendStep(new GeneratedCase(
                s.verifyOp(), s.generatorName(), verifyInput, ExpectedOutcome.SUCCESS, null),
                "verify");
        if (verify.terminal() != null) {
            return verify.terminal();
        }
        Variant verifyVariant = verify.variant();
        InvocationResponse verifyResp = verify.resp();

        // Step 3 — parse, validate shape, then check echo.
        JsonNode body;
        try {
            body = verifyResp.body() == null || verifyResp.body().isEmpty()
                    ? JSON.nullNode()
                    : (xmlMode
                            ? XML.readTree(verifyResp.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            : JSON.readTree(verifyResp.body()));
        } catch (IOException e) {
            return new VariantResult(verifyVariant, Verdict.FAIL_SHAPE, verifyResp.httpStatus(),
                    null, "verify body did not parse: " + e.getMessage());
        }
        if (xmlMode) {
            body = ShapeValidator.unwrapXmlResult(body, s.verifyOp().getId().getName());
        }
        // Shape drift takes precedence over echo: if the body itself violates
        // the Smithy contract (e.g. an enum value that isn't declared), the
        // round-trip's premise is invalid regardless of field values.
        var outShapeId = s.verifyOp().getOutputShape();
        if (!outShapeId.toString().equals("smithy.api#Unit")) {
            var outShape = model.expectShape(outShapeId);
            if (outShape instanceof StructureShape outStruct) {
                ShapeValidator.Result shapeResult = shapeValidator.validate(body, outStruct);
                if (!shapeResult.ok()) {
                    return new VariantResult(verifyVariant, Verdict.FAIL_SHAPE,
                            verifyResp.httpStatus(), null,
                            summarizeIssues(shapeResult.issues()));
                }
            }
        }
        List<String> mismatches = new ArrayList<>();
        for (String path : s.echoedPaths()) {
            JsonNode expected = setupInput.get(path);
            JsonNode actual = body == null ? null : body.get(path);
            if (expected == null) {
                continue;
            }
            if (actual == null || !echoEquals(expected, unwrapXmlCollection(expected, actual))) {
                mismatches.add(path + " (set " + valueToString(expected)
                        + ", got " + valueToString(actual) + ")");
            }
        }
        if (mismatches.isEmpty()) {
            return new VariantResult(verifyVariant, Verdict.PASS, verifyResp.httpStatus(), null, null);
        }
        return new VariantResult(verifyVariant, Verdict.FAIL_ECHO, verifyResp.httpStatus(),
                null, String.join("; ", mismatches));
    }

    /**
     * awsQuery XML serializes lists as {@code <Items><member>...</member></Items>},
     * which the XmlMapper decodes to {@code {"member": <one element or array>}}.
     * When the expected value is an array, unwrap that single-key indirection
     * so the echo comparison sees the same shape on both sides.
     */
    private JsonNode unwrapXmlCollection(JsonNode expected, JsonNode actual) {
        if (!xmlMode || actual == null || !expected.isArray()
                || !actual.isObject() || actual.size() != 1) {
            return actual;
        }
        JsonNode inner = actual.elements().next();
        if (inner.isArray()) {
            return inner;
        }
        // Single-element list: XML collapses it to the bare element.
        return JSON.createArrayNode().add(inner);
    }

    private static boolean echoEquals(JsonNode expected, JsonNode actual) {
        if (actual == null) {
            return false;
        }
        if (expected.isTextual() && actual.isTextual()) {
            return expected.asText().equals(actual.asText());
        }
        return expected.equals(actual);
    }

    private static String valueToString(JsonNode n) {
        if (n == null) {
            return "<missing>";
        }
        String text = n.toString();
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }

    private static Variant placeholderVariant(GeneratedCase g) {
        return new Variant(g.operation(), g.generator(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, g.expectedOutcome(), g.expectedError());
    }

    private VariantResult classify(Variant variant, InvocationResponse resp) {
        if (resp.is5xx()) {
            String rawType = extractErrorType(resp);
            if (rawType != null && errorClassifier.classify(
                    variant.operation(), resp.httpStatus(), rawType) == Category.NOT_IMPLEMENTED) {
                return new VariantResult(variant, Verdict.NOT_IMPLEMENTED, resp.httpStatus(),
                        rawType, "operation not implemented ("
                        + ErrorClassifier.normalize(rawType) + ")");
            }
            return new VariantResult(variant, Verdict.FAIL_5XX, resp.httpStatus(),
                    rawType, truncate(resp.body()));
        }
        if (resp.is4xx()) {
            String rawType = extractErrorType(resp);
            if (rawType == null) {
                return new VariantResult(variant, Verdict.FAIL_4XX_UNROUTED, resp.httpStatus(),
                        null, truncate(resp.body()));
            }
            String normalized = ErrorClassifier.normalize(rawType);
            Category category = errorClassifier.classify(
                    variant.operation(), resp.httpStatus(), rawType);
            boolean negative = variant.expectedOutcome() == ExpectedOutcome.CLIENT_ERROR;

            if (category == Category.NOT_IMPLEMENTED) {
                return new VariantResult(variant, Verdict.NOT_IMPLEMENTED, resp.httpStatus(),
                        rawType, "operation not implemented (" + normalized + ")");
            }

            if (negative) {
                if (variant.expectedError() != null) {
                    if (variant.expectedError().equals(normalized)) {
                        return new VariantResult(variant, Verdict.PASS, resp.httpStatus(),
                                rawType, null);
                    }
                    // The variant names one specific error (e.g. a Smithy
                    // @examples error case). A different error type is a real
                    // drift — except state/seed noise, which stays inconclusive.
                    return switch (category) {
                        case STATE -> new VariantResult(
                                variant, Verdict.INCONCLUSIVE_STATE, resp.httpStatus(), rawType,
                                "state collision masked negative test (" + normalized + ")");
                        case MISSING -> new VariantResult(
                                variant, Verdict.INCONCLUSIVE_MISSING, resp.httpStatus(), rawType,
                                "synthetic identifier not seeded (" + normalized + ")");
                        default -> new VariantResult(
                                variant, Verdict.FAIL_WRONG_ERROR_TYPE, resp.httpStatus(), rawType,
                                "expected " + variant.expectedError() + ", got " + normalized);
                    };
                }
                return switch (category) {
                    case VALIDATION, DECLARED_BY_OP -> new VariantResult(
                            variant, Verdict.PASS, resp.httpStatus(), rawType, null);
                    case STATE -> new VariantResult(
                            variant, Verdict.INCONCLUSIVE_STATE, resp.httpStatus(), rawType,
                            "state collision masked negative test (" + normalized + ")");
                    case MISSING -> new VariantResult(
                            variant, Verdict.INCONCLUSIVE_MISSING, resp.httpStatus(), rawType,
                            "synthetic identifier not seeded (" + normalized + ")");
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
                case MISSING -> new VariantResult(
                        variant, Verdict.INCONCLUSIVE_MISSING, resp.httpStatus(), rawType,
                        "synthetic identifier not seeded (" + normalized + ")");
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
            return validateShape(variant, resp);
        }
        return new VariantResult(variant, Verdict.HARNESS_ERROR, resp.httpStatus(),
                null, "unexpected status " + resp.httpStatus());
    }

    private VariantResult validateShape(Variant variant, InvocationResponse resp) {
        var outputShapeId = variant.operation().getOutputShape();
        if (outputShapeId.toString().equals("smithy.api#Unit")) {
            return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), null, null);
        }
        var rawShape = model.expectShape(outputShapeId);
        if (!(rawShape instanceof StructureShape outputShape)) {
            return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), null, null);
        }
        if (resp.body() == null || resp.body().isEmpty()) {
            ShapeValidator.Result emptyResult = shapeValidator.validate(JSON.nullNode(), outputShape);
            if (emptyResult.ok()) {
                return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), null, null);
            }
            return new VariantResult(variant, Verdict.FAIL_SHAPE, resp.httpStatus(), null,
                    "2xx with empty body but @required members declared: "
                            + summarizeIssues(emptyResult.issues()));
        }
        JsonNode root;
        try {
            root = xmlMode ? XML.readTree(resp.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)) : JSON.readTree(resp.body());
        } catch (IOException e) {
            return new VariantResult(variant, Verdict.FAIL_SHAPE, resp.httpStatus(), null,
                    "2xx body did not parse as "
                            + (xmlMode ? "XML" : "JSON") + ": " + e.getMessage());
        }
        if (xmlMode) {
            root = ShapeValidator.unwrapXmlResult(root, variant.operationName());
        }
        ShapeValidator.Result result = shapeValidator.validate(root, outputShape);
        if (result.ok()) {
            return new VariantResult(variant, Verdict.PASS, resp.httpStatus(), null, null);
        }
        return new VariantResult(variant, Verdict.FAIL_SHAPE, resp.httpStatus(), null,
                summarizeIssues(result.issues()));
    }

    private static String summarizeIssues(java.util.List<ShapeValidator.Issue> issues) {
        if (issues.isEmpty()) {
            return "no issues";
        }
        int show = Math.min(3, issues.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < show; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ShapeValidator.Issue is = issues.get(i);
            sb.append(is.path()).append(" ").append(is.message());
        }
        if (issues.size() > show) {
            sb.append(" (+").append(issues.size() - show).append(" more)");
        }
        return sb.toString();
    }

    private static String extractErrorType(InvocationResponse resp) {
        if (resp.body() == null || resp.body().isEmpty()) {
            return null;
        }
        String ct = resp.contentType() == null ? "" : resp.contentType().toLowerCase();
        try {
            if (ct.contains("xml") || resp.body().startsWith("<")) {
                JsonNode root = XML.readTree(resp.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
