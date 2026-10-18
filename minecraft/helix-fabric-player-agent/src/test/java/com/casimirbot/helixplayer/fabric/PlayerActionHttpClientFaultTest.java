package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PlayerActionHttpClientFaultTest {
    @Test
    void projectionDeliveryWaitsForACommittedSlowReceiptWithoutReplaying() throws Exception {
        assertTrue(PlayerActionHttpClient.PROJECTION_DELIVERY_TIMEOUT.compareTo(Duration.ofSeconds(12)) > 0);
        var serverWorkers = java.util.concurrent.Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/events/batch", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            try { Thread.sleep(13_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"ok\":true,\"schema\":\"helix.environment_event_batch_receipt.v1\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new PlayerActionHttpClient(config(server.getAddress().getPort()))) {
            assertTrue(client.postProjection("/events/batch", Map.of()).ok());
            assertEquals(1, calls.get(), "The client must wait for the receipt, not replay the POST");
        } finally {
            server.stop(0);
            serverWorkers.shutdownNow();
        }
    }

    @Test
    void malformedSuccessIsNotAnEmptyDelivery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        String[] bodies = {"{\"ok\":true}", "{\"ok\":true,\"action_request\":false}",
            "{\"ok\":true,\"action_request\":[]}", "not json",
            "{\"ok\":true,\"action_request\":null}",
            "{\"ok\":true,\"action_request\":{\"action_request_id\":\"fixture\"}}"};
        server.createContext("/requests/temporal-successor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = bodies[calls.getAndIncrement()].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new PlayerActionHttpClient(config(server.getAddress().getPort()))) {
            for (int index = 0; index < bodies.length; index++) {
                var response = client.post("/requests/temporal-successor", Map.of());
                assertEquals(index < 4, PlayerActionRuntime.temporalResponseUncertain(response), bodies[index]);
                assertEquals(index + 1, calls.get(), "Transport must not retry malformed responses internally");
            }
        } finally { server.stop(0); }
        // An object passes transport classification only; existing native
        // identity/hash/deadline preflight still owns its admissibility.
        assertTrue(PlayerActionRuntime.temporalResponseUncertain(
            new PlayerActionHttpClient.Response(500, Map.of("ok", true, "action_request", Map.of()))));
    }
    @Test
    void controlHttpCompletesWhileSuccessorHttpIsBlocked() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var serverWorkers = java.util.concurrent.Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/requests/temporal-successor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/controls/pending", exchange -> {
            byte[] response = "{\"ok\":true,\"control_requests\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = new PlayerActionHttpClient(config(server.getAddress().getPort()));
             var lane = new TemporalDeliveryLane()) {
            assertTrue(lane.submit(() -> {
                try { client.post("/requests/temporal-successor", Map.of()); }
                catch (IOException expected) { /* fixture deliberately drops response */ }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(client.get("/controls/pending?limit=4").ok());
            assertEquals(1L, release.getCount(), "Control completed before successor was released");
            assertFalse(lane.submit(() -> fail("No duplicate successor work")));
        } finally {
            release.countDown();
            server.stop(0);
            serverWorkers.shutdownNow();
        }
    }

    @Test
    void absentOrUntrustedReconciliationNeverBecomesSuccess() {
        assertEquals("unresolved", PlayerActionRuntime.reconciliationStatus(
            new PlayerActionHttpClient.Response(200, Map.of("ok", true))));
        assertEquals("unresolved", PlayerActionRuntime.reconciliationStatus(
            new PlayerActionHttpClient.Response(401, Map.of("ok", false))));
        assertEquals("unresolved", PlayerActionRuntime.reconciliationStatus(
            new PlayerActionHttpClient.Response(200, Map.of("ok", true,
                "delivery_state", Map.of("recorded_status", "execute this payload")))));
    }

    @Test
    void lostSuccessorResponseRemainsUncertainAndDoesNotReplayTheLease() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger leases = new AtomicInteger();
        server.createContext("/requests/temporal-successor/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] state = "{\"ok\":true,\"delivery_state\":{\"recorded_status\":\"leased\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, state.length);
            exchange.getResponseBody().write(state);
            exchange.close();
        });
        // Transport fault fixture, not a substitute for broker admission tests.
        // Model the existing one-shot admitted -> leased CAS at the endpoint.
        server.createContext("/requests/temporal-successor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            if (leases.compareAndSet(0, 1)) {
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write("{\"ok\":true,".getBytes(StandardCharsets.UTF_8));
                exchange.close(); // server committed, executable response lost
                return;
            }
            byte[] empty = "{\"ok\":true,\"action_request\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, empty.length);
            exchange.getResponseBody().write(empty);
            exchange.close();
        });
        server.start();
        try (PlayerActionHttpClient client = new PlayerActionHttpClient(config(server.getAddress().getPort()))) {
            assertThrows(IOException.class, () -> client.post("/requests/temporal-successor", Map.of("checkpoint_id", "fixture")));
            assertEquals(1, requests.get(), "No hidden POST replay after truncated response");
            assertEquals(1, leases.get(), "IOException does not prove non-admission");
            var reconciled = client.post("/requests/temporal-successor/status", Map.of("checkpoint_id", "fixture"));
            assertEquals("recorded_leased", PlayerActionRuntime.reconciliationStatus(reconciled));
            assertEquals(1, requests.get(), "Reconciliation is not a delivery request");
            var laterPoll = client.post("/requests/temporal-successor", Map.of("checkpoint_id", "fixture"));
            assertTrue(laterPoll.ok());
            assertNull(laterPoll.body().get("action_request"));
            assertEquals(1, leases.get());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static PlayerActionConfig config(int port) {
        return new PlayerActionConfig("http://127.0.0.1:" + port,
            "helix_env_action_" + "x".repeat(43), "authority:fixture", "installation:fixture",
            "environment:fixture", "room:fixture", "source:fixture", "world:fixture",
            "adapter:fixture", PlayerActionConfig.DOMAIN_ADAPTER, "participant:fixture",
            "subject:fixture", "player:fixture", 1, Instant.now().plusSeconds(120).toString());
    }
}
