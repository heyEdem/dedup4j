package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.DashboardDatabase;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ContentProxyControllerTest {
    private static final UUID CONTENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @TempDir Path tempDir;
    private HttpServer server;
    private InstanceRepository instances;
    private MockMvc mvc;
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The fake echoes the forwarded query so tests see exactly what reached the instance.
        server.createContext("/orders/v1/contents", exchange -> {
            upstreamCalls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/orders/v1/contents")) {
                String query = exchange.getRequestURI().getRawQuery();
                respond(exchange, 200, "{\"items\":[],\"query\":\"" + (query == null ? "" : query) + "\"}");
            } else if (path.equals("/orders/v1/contents/" + CONTENT)) {
                respond(exchange, 200, "{\"id\":\"" + CONTENT + "\",\"objectKey\":\"sha256/ab\"}");
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.createContext("/rejecting/v1/contents", exchange -> respond(exchange, 400, "{}"));
        server.createContext("/secured/v1/contents", exchange -> respond(exchange, 401, "{}"));
        server.createContext("/garbled/v1/contents", exchange -> respond(exchange, 200, "not json"));
        server.start();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("dashboard.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        instances = new InstanceRepository(jdbc);
        mvc = MockMvcBuilders.standaloneSetup(new ContentProxyController(instances)).build();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void listForwardsOnlyTheGivenParameters() throws Exception {
        UUID orders = register("orders");

        assertThat(json(call("/api/v1/instances/{id}/contents?sort=size&page=2&size=500", orders)).get("query").asString())
                .isEqualTo("sort=size&page=2&size=500");
        assertThat(json(call("/api/v1/instances/{id}/contents", orders)).get("query").asString()).isEmpty();
    }

    @Test
    void detailIsPassedThroughAndAMissingRowIsNotFound() throws Exception {
        UUID orders = register("orders");

        assertThat(json(call("/api/v1/instances/{id}/contents/" + CONTENT, orders)).get("objectKey").asString()).isEqualTo("sha256/ab");
        assertThat(call("/api/v1/instances/{id}/contents/" + UUID.randomUUID(), orders).getStatus()).isEqualTo(404);
    }

    @Test
    void anUnknownInstanceIsNotFoundWithoutAnyUpstreamCall() throws Exception {
        assertThat(call("/api/v1/instances/{id}/contents", UUID.randomUUID()).getStatus()).isEqualTo(404);
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void anInstanceWithoutContentListingIsNotImplemented() throws Exception {
        assertThat(call("/api/v1/instances/{id}/contents", register("older")).getStatus()).isEqualTo(501);
    }

    @Test
    void invalidParametersAreRejectedBeforeAnyUpstreamCall() throws Exception {
        UUID orders = register("orders");

        assertThat(call("/api/v1/instances/{id}/contents?sort=name", orders).getStatus()).isEqualTo(400);
        assertThat(call("/api/v1/instances/{id}/contents?page=one", orders).getStatus()).isEqualTo(400);
        assertThat(call("/api/v1/instances/{id}/contents/not-a-uuid", orders).getStatus()).isEqualTo(400);
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void anUpstreamBadRequestStaysABadRequest() throws Exception {
        assertThat(call("/api/v1/instances/{id}/contents?page=1", register("rejecting")).getStatus()).isEqualTo(400);
    }

    @Test
    void unreachableSecuredOrGarbledInstancesAreBadGateway() throws Exception {
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int closedPort = closed.getAddress().getPort();
        closed.stop(0);
        UUID gone = UUID.randomUUID();
        instances.save(gone, "gone", "http://127.0.0.1:" + closedPort + "/gone/v1", Instant.now());

        assertThat(call("/api/v1/instances/{id}/contents", gone).getStatus()).isEqualTo(502);
        assertThat(call("/api/v1/instances/{id}/contents", register("secured")).getStatus()).isEqualTo(502);
        assertThat(call("/api/v1/instances/{id}/contents", register("garbled")).getStatus()).isEqualTo(502);
    }

    private UUID register(String name) {
        UUID id = UUID.randomUUID();
        instances.save(id, name, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + "/v1/", Instant.now());
        return id;
    }

    private MockHttpServletResponse call(String uri, UUID instance) throws Exception {
        return mvc.perform(get(uri, instance)).andReturn().getResponse();
    }

    private static tools.jackson.databind.JsonNode json(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
