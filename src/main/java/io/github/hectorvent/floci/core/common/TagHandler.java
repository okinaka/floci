package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Map;

/**
 * Per-service handler for the REST tag endpoints that share the {@code /tags/{resourceArn}}
 * path (API Gateway, EventBridge Scheduler, EFS, etc.).
 *
 * <p>A single {@code SharedTagsController} routes all {@code /tags/{arn}} requests and
 * dispatches to the implementation whose {@link #serviceKey()} matches the credential
 * scope extracted from the request's Authorization header.
 *
 * <p>Implementations are responsible for parsing their own ARN format and raising
 * {@link io.github.hectorvent.floci.core.common.AwsException} on invalid input.
 */
public interface TagHandler {

    /**
     * Credential scope this handler responds to (e.g. {@code "apigateway"},
     * {@code "scheduler"}). Matched against the value the dispatcher extracts from the
     * request's Authorization header.
     */
    String serviceKey();

    Map<String, String> listTags(String region, String arn);

    void tagResource(String region, String arn, Map<String, String> tags);

    void untagResource(String region, String arn, List<String> tagKeys);
}
