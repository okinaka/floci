package io.floci.conformance.coverage;

import io.floci.conformance.classify.ErrorClassifier;
import io.floci.conformance.classify.ErrorClassifier.Category;
import io.floci.conformance.classify.ErrorTypes;
import io.floci.conformance.invoke.InvocationResponse;
import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpTrait;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps one probe response to a {@link CoverageStatus}. Only dispatch is
 * judged: a validation error or a missing resource proves the operation is
 * routed to a handler just as well as a 2xx does.
 */
final class CoverageClassifier {

    /**
     * Not-implemented codes beyond the shared {@link ErrorClassifier} list.
     * Floci's CloudFormation handler answers unknown actions with {@code UnknownAction}.
     */
    private static final Set<String> EXTRA_NOT_IMPLEMENTED = Set.of("UnknownAction");

    /**
     * A message that names the operation and says it is unsupported is a
     * not-implemented signal whatever the error code: Floci's Redshift Data
     * handler sends {@code ValidationException} with "Operation X is not supported".
     */
    private static final Pattern NOT_SUPPORTED_MESSAGE = Pattern.compile(
            "not (yet )?(supported|implemented)|unknown (operation|action)|unsupported (operation|action)");

    /**
     * Errors only S3 returns. Path-style S3 serves {@code /{Bucket}/{Key+}} on
     * the shared port, so a REST path no other handler claims lands there; one
     * of these from another service means S3 answered, not that service.
     */
    private static final Set<String> S3_ONLY_ERRORS = Set.of(
            "NoSuchBucket", "NoSuchKey", "NoSuchUpload", "BucketAlreadyExists",
            "BucketAlreadyOwnedByYou", "InvalidBucketName");

    private final ErrorClassifier errorClassifier = new ErrorClassifier();
    private final Set<String> serviceErrors = new HashSet<>();
    private final boolean isS3;

    CoverageClassifier(Model model, ServiceShape service) {
        for (StructureShape s : model.getStructureShapesWithTrait(ErrorTrait.class)) {
            serviceErrors.add(stripSuffix(s.getId().getName()));
            s.getTrait(AwsQueryErrorTrait.class)
                    .ifPresent(t -> serviceErrors.add(stripSuffix(ErrorClassifier.normalize(t.getCode()))));
        }
        this.isS3 = service.getId().getNamespace().equals("com.amazonaws.s3");
    }

    CoverageStatus classify(OperationShape op, InvocationResponse resp) {
        int status = resp.httpStatus();
        String rawType = ErrorTypes.extract(resp);
        if (status == 501 || status == 405) {
            return CoverageStatus.NOT_IMPLEMENTED;
        }
        if (rawType != null && isNotImplementedType(op, status, rawType)) {
            return CoverageStatus.NOT_IMPLEMENTED;
        }
        if (!resp.is2xx() && saysUnsupported(op, resp)) {
            return CoverageStatus.NOT_IMPLEMENTED;
        }
        if (resp.is2xx() || status / 100 == 3) {
            return CoverageStatus.IMPLEMENTED;
        }
        if (resp.is5xx()) {
            return CoverageStatus.SERVER_ERROR;
        }
        if (rawType != null) {
            String name = stripSuffix(ErrorClassifier.normalize(rawType));
            boolean foreign = !isS3 && S3_ONLY_ERRORS.contains(name) && !serviceErrors.contains(name);
            return foreign ? CoverageStatus.FOREIGN_ERROR : CoverageStatus.IMPLEMENTED;
        }
        if (isHead(op)) {
            return CoverageStatus.AMBIGUOUS;
        }
        // 404: no route. 406: a route on the path, but none producing this
        // protocol's media type (typically S3's catch-all refusing a JSON Accept).
        return status == 404 || status == 406 ? CoverageStatus.NOT_IMPLEMENTED : CoverageStatus.AMBIGUOUS;
    }

    private boolean isNotImplementedType(OperationShape op, int status, String rawType) {
        return errorClassifier.classify(op, status, rawType) == Category.NOT_IMPLEMENTED
                || EXTRA_NOT_IMPLEMENTED.contains(ErrorClassifier.normalize(rawType));
    }

    private static boolean saysUnsupported(OperationShape op, InvocationResponse resp) {
        String message = ErrorTypes.message(resp);
        if (message == null || !message.contains(op.getId().getName())) {
            return false;
        }
        return NOT_SUPPORTED_MESSAGE.matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    private static String stripSuffix(String name) {
        return name.endsWith("Exception") ? name.substring(0, name.length() - "Exception".length()) : name;
    }

    private static boolean isHead(OperationShape op) {
        return op.getTrait(HttpTrait.class)
                .map(t -> "HEAD".equalsIgnoreCase(t.getMethod()))
                .orElse(false);
    }
}
