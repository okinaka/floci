package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS API Gateway tag endpoints at /tags/{resourceArn}.
 *
 * <p>HTTP and JSON concerns live here; ARN parsing and storage delegation live in
 * {@link ApiGatewayTagHandler}. A later refactor will move the {@code /tags/{arn}} route
 * to a shared dispatcher so that Scheduler/EFS can coexist on the same path.
 */
@Path("/tags")
@Produces(MediaType.APPLICATION_JSON)
public class ApiGatewayTagsController {

    private final ApiGatewayTagHandler tagHandler;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayTagsController(ApiGatewayTagHandler tagHandler,
                                    RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.tagHandler = tagHandler;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{arn: .*}")
    public Response getTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = tagHandler.listTags(region, arn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    @PUT
    @Path("/{arn: .*}")
    public Response tagResource(@Context HttpHeaders headers,
                                @PathParam("arn") String arn,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode node = objectMapper.readTree(body);
            Map<String, String> tags = new HashMap<>();
            node.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
            tagHandler.tagResource(region, arn, tags);
            return Response.noContent().build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/{arn: .*}")
    public Response untagResource(@Context HttpHeaders headers,
                                  @PathParam("arn") String arn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        tagHandler.untagResource(region, arn, tagKeys);
        return Response.noContent().build();
    }
}
