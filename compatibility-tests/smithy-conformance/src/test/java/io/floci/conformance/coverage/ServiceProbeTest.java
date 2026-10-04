package io.floci.conformance.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link ServiceProbe} against a JDK {@link HttpServer} standing in for
 * an emulator, to pin down protocol selection, the canary, and the no-route
 * calibration that keeps catch-all routes from counting as implementations.
 */
class ServiceProbeTest {

    private HttpServer server;
    private Function<HttpExchange, String[]> handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String[] reply = handler.apply(exchange);
            byte[] body = reply[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(reply[0]), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Map<String, CoverageStatus> byOperation(List<OperationCoverage> results) {
        return results.stream().collect(Collectors.toMap(OperationCoverage::operation, OperationCoverage::status));
    }

    private static final Model JSON_MODEL = Model.assembler()
            .addUnparsedModel("json.smithy", """
                    $version: "2"
                    namespace test.json
                    use aws.protocols#awsJson1_1

                    @awsJson1_1
                    service Gadgets {
                        version: "2020-01-01"
                        operations: [ListGadgets, DeleteGadget]
                    }

                    operation ListGadgets {}
                    operation DeleteGadget { input := { @required Name: String } }
                    """)
            .discoverModels()
            .assemble()
            .unwrap();

    private ServiceProbe jsonProbe() {
        return new ServiceProbe("gadgets", JSON_MODEL,
                JSON_MODEL.expectShape(ShapeId.from("test.json#Gadgets"), ServiceShape.class), baseUrl());
    }

    @Test
    void awsJson_dispatchesByTarget_andCanaryIsNotImplemented() {
        handler = exchange -> {
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            return switch (target) {
                case "Gadgets.ListGadgets" -> new String[] {"200", "{\"Gadgets\":[]}"};
                case "Gadgets.DeleteGadget" -> new String[] {"400",
                        "{\"__type\":\"ResourceNotFoundException\",\"message\":\"gadget cov-probe not found\"}"};
                default -> new String[] {"400",
                        "{\"__type\":\"UnknownOperationException\",\"message\":\"" + target + "\"}"};
            };
        };
        ServiceProbe probe = jsonProbe();

        assertThat(probe.protocols()).containsExactly("awsJson1_1");
        assertThat(probe.canary()).isEqualTo(CoverageStatus.NOT_IMPLEMENTED);
        assertThat(byOperation(probe.run())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ListGadgets", CoverageStatus.IMPLEMENTED,
                "DeleteGadget", CoverageStatus.IMPLEMENTED));
    }

    @Test
    void awsJson_errorIdenticalToMadeUpOperation_isNotImplemented() {
        // An emulator that answers every unknown target with a plausible-looking
        // validation error: only the calibration against the canary sees through it.
        handler = exchange -> {
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            if (target.equals("Gadgets.ListGadgets")) {
                return new String[] {"200", "{}"};
            }
            String op = target.substring(target.indexOf('.') + 1);
            return new String[] {"400",
                    "{\"__type\":\"ValidationException\",\"message\":\"Cannot handle " + op + "\"}"};
        };

        assertThat(byOperation(jsonProbe().run())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ListGadgets", CoverageStatus.IMPLEMENTED,
                "DeleteGadget", CoverageStatus.NOT_IMPLEMENTED));
    }

    @Test
    void awsJson_madeUpOperationAccepted_marksCanary() {
        handler = exchange -> new String[] {"200", "{}"};

        assertThat(jsonProbe().canary()).isEqualTo(CoverageStatus.IMPLEMENTED);
    }

    private static final Model REST_MODEL = Model.assembler()
            .addUnparsedModel("rest.smithy", """
                    $version: "2"
                    namespace test.rest
                    use aws.protocols#restJson1

                    @restJson1
                    service Things {
                        version: "2020-01-01"
                        operations: [GetThing, ListParts]
                    }

                    @http(method: "GET", uri: "/v1/things/{Id}")
                    @readonly
                    operation GetThing { input := { @required @httpLabel Id: String } }

                    @http(method: "GET", uri: "/v1/parts")
                    @readonly
                    operation ListParts {}
                    """)
            .discoverModels()
            .assemble()
            .unwrap();

    @Test
    void restJson_catchAllAnsweringEveryUnclaimedPath_isNotImplemented() {
        // fakecloud's execute-api route: an unclaimed /{stage}/... path is
        // "Stage not found: <first segment>", a 404 with a real AWS error type.
        handler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/v1/things/")) {
                return new String[] {"404",
                        "{\"__type\":\"NotFoundException\",\"message\":\"Thing cov-probe-x does not exist\"}"};
            }
            String stage = path.split("/")[1];
            return new String[] {"404",
                    "{\"__type\":\"NotFoundException\",\"message\":\"Stage not found: " + stage + "\"}"};
        };
        ServiceProbe probe = new ServiceProbe("things", REST_MODEL,
                REST_MODEL.expectShape(ShapeId.from("test.rest#Things"), ServiceShape.class), baseUrl());

        assertThat(probe.canary()).isNull();
        assertThat(byOperation(probe.run())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GetThing", CoverageStatus.IMPLEMENTED,
                "ListParts", CoverageStatus.NOT_IMPLEMENTED));
    }
}
