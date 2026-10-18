package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayerActionRestartTransportTest {
    @TempDir Path directory;

    @Test
    void heartbeatAndQueryPollsFollowTheSameRestartedServiceWithoutReplay() throws Exception {
        HttpServer old = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer replacement = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> oldCalls = Collections.synchronizedList(new ArrayList<>());
        List<String> newCalls = Collections.synchronizedList(new ArrayList<>());
        String token = "helix_env_action_" + "x".repeat(43);
        for (HttpServer server : List.of(old, replacement)) {
            List<String> calls = server == old ? oldCalls : newCalls;
            server.createContext("/authority/fixture", exchange -> {
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " +
                    exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }
        Path receipt = directory.resolve("ready.json");
        Files.writeString(receipt, """
            {"schema":"casimir_desktop_service_ready_receipt/1","ready":true,
             "origin":"http://127.0.0.1:%d","serviceProcessId":%d}
            """.formatted(replacement.getAddress().getPort(), ProcessHandle.current().pid()));
        PlayerActionConfig config = new PlayerActionConfig(
            "http://127.0.0.1:" + old.getAddress().getPort() + "/authority/fixture",
            token, "authority:fixture", "installation:fixture", "environment:fixture",
            "room:fixture", "source:fixture", "world:fixture", "adapter:fixture",
            PlayerActionConfig.DOMAIN_ADAPTER, "participant:fixture", "subject:fixture",
            "player:fixture", 1, Instant.now().plusSeconds(120).toString());
        try (var client = new PlayerActionHttpClient(config,
                endpoint -> InstalledDesktopServiceEndpointResolver.resolve(endpoint, receipt))) {
            assertTrue(client.get("/controls/pending?limit=4").ok());
            assertEquals(1, oldCalls.size(), "A live paired origin remains authoritative");
            assertTrue(newCalls.isEmpty());
            old.stop(0);
            assertTrue(client.post("/heartbeat", Map.of()).ok());
            assertTrue(client.get("/controls/pending?limit=4").ok());
            assertTrue(client.get("/requests/pending?limit=1").ok());
            assertEquals(List.of(
                "POST /authority/fixture/heartbeat Bearer " + token,
                "GET /authority/fixture/controls/pending?limit=4 Bearer " + token,
                "GET /authority/fixture/requests/pending?limit=1 Bearer " + token), newCalls);
            assertEquals(1, oldCalls.size());
        } finally {
            old.stop(0);
            replacement.stop(0);
        }
    }
}
