package io.floci.conformance.coverage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.conformance.classify.ErrorClassifier;
import io.floci.conformance.classify.ErrorTypes;
import io.floci.conformance.encode.AwsJsonEncoder;
import io.floci.conformance.encode.QueryFormEncoder;
import io.floci.conformance.encode.RequestEncoder;
import io.floci.conformance.encode.RestJsonEncoder;
import io.floci.conformance.encode.RestXmlEncoder;
import io.floci.conformance.encode.RpcV2CborEncoder;
import io.floci.conformance.generator.GeneratedCase;
import io.floci.conformance.invoke.AwsJsonInvoker;
import io.floci.conformance.invoke.InvocationResponse;
import io.floci.conformance.invoke.Invoker;
import io.floci.conformance.invoke.QueryInvoker;
import io.floci.conformance.invoke.RestJsonInvoker;
import io.floci.conformance.invoke.RestXmlInvoker;
import io.floci.conformance.invoke.RpcV2CborInvoker;
import io.floci.conformance.model.ExpectedOutcome;
import io.floci.conformance.model.Variant;
import io.floci.conformance.synth.InputSynthesizer;
import software.amazon.smithy.aws.traits.ServiceTrait;
import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.pattern.SmithyPattern.Segment;
import software.amazon.smithy.model.pattern.UriPattern;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpTrait;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Probes every operation of one service once and reports whether the emulator
 * dispatches it. No seeding, no ordering, no expected outcome: the only
 * question is whether the request reached a handler.
 *
 * <p>The probe input is the operation's {@code @required} members, so routes
 * that are told apart by a required header or query parameter (S3
 * {@code CopyObject} versus {@code PutObject}) reach the right handler. If that
 * input cannot be encoded, a probe with only the {@code @httpLabel} members is
 * sent instead.
 *
 * <p>A service that declares several protocols is probed in the order AWS
 * SDKs prefer them, moving to the next protocol only while the operation looks
 * unimplemented, so an emulator that serves CloudWatch over Query alone is not
 * marked down for lacking CBOR.
 *
 * <p>Error responses are calibrated against a request that cannot reach a
 * handler (see {@link #noRoute}): emulators answer unclaimed paths through
 * catch-all routes with plausible AWS errors, and only the comparison tells
 * those apart from a real handler's reply.
 */
final class ServiceProbe {

    /** AWS SDK protocol preference order. */
    private static final List<String> PROTOCOLS = List.of(
            "smithy.protocols#rpcv2Cbor",
            "aws.protocols#awsJson1_0",
            "aws.protocols#awsJson1_1",
            "aws.protocols#restJson1",
            "aws.protocols#restXml",
            "aws.protocols#awsQuery",
            "aws.protocols#ec2Query");

    private static final String LABEL_PLACEHOLDER = "cov-probe-x";
    private static final String CANARY_OPERATION = "CoverageCanaryNonexistentOperation";
    /** Protocols that select the operation by name, where a made-up name is a clean canary. */
    private static final Set<String> RPC_PROTOCOLS = Set.of(
            "rpcv2Cbor", "awsJson1_0", "awsJson1_1", "awsQuery", "ec2Query");

    private static final String CANARY_SEGMENT = "coverage-canary-x9";

    private record Transport(String protocol, RequestEncoder encoder, Invoker invoker) {
    }

    private record Fingerprint(int status, String type, String message) {
    }

    private final String serviceKey;
    private final Model model;
    private final ServiceShape service;
    private final List<Transport> transports;
    private final CoverageClassifier classifier;
    private final Map<String, Fingerprint> noRouteCache = new HashMap<>();

    ServiceProbe(String serviceKey, Model model, ServiceShape service, String baseUrl) {
        this.serviceKey = serviceKey;
        this.model = model;
        this.service = service;
        this.transports = transportsFor(model, service, baseUrl);
        this.classifier = new CoverageClassifier(model, service);
    }

    /** Short names of the protocols this service will be probed with, in order. */
    List<String> protocols() {
        return transports.stream().map(Transport::protocol).toList();
    }

    /**
     * How the emulator answered a made-up operation over the first RPC-style
     * protocol: {@link CoverageStatus#IMPLEMENTED} if it replied 2xx, which
     * makes this service's implemented count an upper bound, otherwise
     * {@link CoverageStatus#NOT_IMPLEMENTED}. {@code null} for REST-only
     * services, whose operations are selected by path rather than by name.
     */
    CoverageStatus canary() {
        for (Transport t : transports) {
            if (RPC_PROTOCOLS.contains(t.protocol())) {
                Fingerprint f = noRoute(t, null);
                if (f == null) {
                    return CoverageStatus.PROBE_FAILED;
                }
                return f.status() / 100 == 2 ? CoverageStatus.IMPLEMENTED : CoverageStatus.NOT_IMPLEMENTED;
            }
        }
        return null;
    }

    List<OperationCoverage> run() {
        List<OperationCoverage> results = new ArrayList<>();
        // TopDownIndex, not getAllOperations(): the latter misses operations bound
        // through resources, which is most of Lambda.
        TreeSet<OperationShape> ops = new TreeSet<>(TopDownIndex.of(model).getContainedOperations(service));
        for (OperationShape op : ops) {
            results.add(probe(op));
        }
        return results;
    }

    private OperationCoverage probe(OperationShape op) {
        if (transports.isEmpty()) {
            return new OperationCoverage(serviceKey, op.getId().getName(), "none",
                    CoverageStatus.PROBE_FAILED, -1, "unsupported protocol");
        }
        OperationCoverage best = null;
        for (Transport t : transports) {
            OperationCoverage result = probe(op, t);
            if (best == null || result.status().ordinal() < best.status().ordinal()) {
                best = result;
            }
            if (best.status() == CoverageStatus.IMPLEMENTED) {
                break;
            }
        }
        return best;
    }

    private OperationCoverage probe(OperationShape op, Transport t) {
        Variant variant;
        try {
            variant = t.encoder().encode(requiredOnlyCase(op));
        } catch (RuntimeException e) {
            try {
                variant = t.encoder().encode(labelsOnlyCase(op));
            } catch (RuntimeException e2) {
                return failed(op, t, "encoder: " + e2.getMessage());
            }
        }
        InvocationResponse resp;
        try {
            resp = t.invoker().send(variant);
        } catch (IOException | RuntimeException e) {
            return failed(op, t, "send: " + e.getMessage());
        }
        String rawType = ErrorTypes.extract(resp);
        String errorType = rawType == null ? null : ErrorClassifier.normalize(rawType);
        CoverageStatus status = classifier.classify(op, resp);
        if (status == CoverageStatus.IMPLEMENTED && !resp.is2xx()
                && fingerprint(resp, op.getId().getName(), firstLiteralSegment(op)).equals(noRoute(t, op))) {
            status = CoverageStatus.NOT_IMPLEMENTED;
            errorType = errorType + " (same as a no-route request)";
        }
        return new OperationCoverage(serviceKey, op.getId().getName(), t.protocol(),
                status, resp.httpStatus(), errorType);
    }

    /**
     * What a request that reaches no handler looks like on this transport,
     * for an operation shaped like {@code op}: a made-up operation name for the
     * RPC protocols, and for REST {@code op}'s method on a made-up path that
     * shares no segment with any AWS route. A probe answered identically is
     * unrouted, however plausible its error looks: an emulator's catch-all
     * route (API Gateway's execute-api {@code /{stage}/...}, S3's
     * {@code /{Bucket}/{Key+}}) answers every unclaimed path the same way.
     * {@code null} when there is nothing to compare: the operation's own path
     * starts with a label (S3's do, so they are the catch-all), or the request
     * could not be sent.
     */
    private Fingerprint noRoute(Transport t, OperationShape op) {
        String key;
        OperationShape canary;
        ShapeId canaryId = ShapeId.fromParts(service.getId().getNamespace(), CANARY_OPERATION);
        if (RPC_PROTOCOLS.contains(t.protocol())) {
            key = t.protocol();
            canary = OperationShape.builder().id(canaryId).build();
        } else {
            HttpTrait http = op == null ? null : op.getTrait(HttpTrait.class).orElse(null);
            if (http == null || firstLiteralSegment(op) == null) {
                return null;
            }
            key = t.protocol() + " " + http.getMethod();
            String path = "/" + CANARY_SEGMENT + "/" + CANARY_SEGMENT;
            canary = OperationShape.builder().id(canaryId)
                    .addTrait(HttpTrait.builder().method(http.getMethod()).uri(UriPattern.parse(path)).code(200).build())
                    .build();
        }
        if (noRouteCache.containsKey(key)) {
            return noRouteCache.get(key);
        }
        Variant variant = new Variant(canary, "coverage.canary", Map.of(), Map.of(), Map.of(),
                null, ExpectedOutcome.SUCCESS, null);
        Fingerprint result;
        try {
            result = fingerprint(t.invoker().send(variant), CANARY_OPERATION, CANARY_SEGMENT);
        } catch (IOException | RuntimeException e) {
            result = null;
        }
        noRouteCache.put(key, result);
        return result;
    }

    /** The first path segment when it is a literal, else {@code null} (no path, or a label). */
    private static String firstLiteralSegment(OperationShape op) {
        List<Segment> segments = op.getTrait(HttpTrait.class)
                .map(h -> h.getUri().getSegments())
                .orElse(List.of());
        return segments.isEmpty() || segments.get(0).isLabel() ? null : segments.get(0).getContent();
    }

    /**
     * Status, error type and message, with the operation's name and first path
     * segment blanked out: catch-all routes echo them ("Stage not found: v1").
     */
    private static Fingerprint fingerprint(InvocationResponse resp, String operationName, String segment) {
        String type = ErrorTypes.extract(resp);
        String message = ErrorTypes.message(resp);
        if (message != null) {
            message = message.replace(operationName, "{op}");
            if (segment != null) {
                message = message.replace(segment, "{seg}");
            }
        }
        return new Fingerprint(resp.httpStatus(), type == null ? null : ErrorClassifier.normalize(type), message);
    }

    private OperationCoverage failed(OperationShape op, Transport t, String detail) {
        return new OperationCoverage(serviceKey, op.getId().getName(), t.protocol(),
                CoverageStatus.PROBE_FAILED, -1, detail);
    }

    private GeneratedCase requiredOnlyCase(OperationShape op) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        Shape inputShape = model.expectShape(op.getInputShape());
        if (inputShape instanceof StructureShape struct) {
            input = new InputSynthesizer(model, InputSynthesizer.requiredOnly(), null)
                    .synthesizeInput(struct);
        }
        return new GeneratedCase(op, "coverage.required-only", input, ExpectedOutcome.SUCCESS, null);
    }

    private GeneratedCase labelsOnlyCase(OperationShape op) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        Shape inputShape = model.expectShape(op.getInputShape());
        if (inputShape instanceof StructureShape struct) {
            for (MemberShape m : struct.getAllMembers().values()) {
                if (m.hasTrait(HttpLabelTrait.class)) {
                    input.put(m.getMemberName(), LABEL_PLACEHOLDER);
                }
            }
        }
        return new GeneratedCase(op, "coverage.labels-only", input, ExpectedOutcome.SUCCESS, null);
    }

    private static List<Transport> transportsFor(Model model, ServiceShape service, String baseUrl) {
        String signingName = signingName(service);
        String shapeName = service.getId().getName();
        List<Transport> result = new ArrayList<>();
        for (String protocol : PROTOCOLS) {
            if (service.findTrait(ShapeId.from(protocol)).isEmpty()) {
                continue;
            }
            String shortName = protocol.substring(protocol.indexOf('#') + 1);
            Transport t = switch (protocol) {
                case "smithy.protocols#rpcv2Cbor" -> new Transport(shortName, new RpcV2CborEncoder(),
                        new RpcV2CborInvoker(baseUrl, shapeName, signingName));
                case "aws.protocols#awsJson1_0" -> new Transport(shortName, AwsJsonEncoder.json10(),
                        new AwsJsonInvoker(baseUrl + "/", shapeName, signingName,
                                AwsJsonInvoker.Flavor.AWS_JSON_1_0));
                case "aws.protocols#awsJson1_1" -> new Transport(shortName, AwsJsonEncoder.json11(),
                        new AwsJsonInvoker(baseUrl + "/", shapeName, signingName,
                                AwsJsonInvoker.Flavor.AWS_JSON_1_1));
                case "aws.protocols#restJson1" -> new Transport(shortName, new RestJsonEncoder(model),
                        new RestJsonInvoker(baseUrl, signingName));
                case "aws.protocols#restXml" -> new Transport(shortName, new RestXmlEncoder(model),
                        new RestXmlInvoker(baseUrl, signingName));
                default -> new Transport(shortName, new QueryFormEncoder(model),
                        new QueryInvoker(baseUrl + "/", service.getVersion(), signingName));
            };
            result.add(t);
        }
        return result;
    }

    /** SigV4 signing name: {@code @sigv4} name, else the endpoint prefix, else the SDK id. */
    static String signingName(ServiceShape service) {
        return service.getTrait(SigV4Trait.class).map(SigV4Trait::getName)
                .or(() -> service.getTrait(ServiceTrait.class).map(ServiceTrait::getEndpointPrefix))
                .orElse(service.getId().getName().toLowerCase(Locale.ROOT));
    }
}
