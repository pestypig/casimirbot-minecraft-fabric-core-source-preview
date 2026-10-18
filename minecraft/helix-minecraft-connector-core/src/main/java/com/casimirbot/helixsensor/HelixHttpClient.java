package com.casimirbot.helixsensor;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

public final class HelixHttpClient implements Closeable {
    private static final int MAX_SAME_IDENTITY_ATTEMPTS = 3;
    static final int MAX_QUEUED_REQUESTS = 16;
    private static final long MAX_QUEUED_AGE_MS = 60_000L;
    private static final long ORDINARY_REQUEST_TIMEOUT_SECONDS = 8L;
    private static final long CONTROL_PLANE_REQUEST_TIMEOUT_SECONDS = 30L;

    public record IngressResponse(
        int statusCode,
        String body,
        String requestId,
        long sequence,
        String errorCode,
        String message,
        boolean ok,
        boolean accepted,
        boolean replayed,
        boolean delivered
    ) {
        public boolean success() {
            return delivered
                && statusCode >= 200
                && statusCode < 300
                && ok
                && accepted;
        }

        public boolean outcomeUnknown() {
            return "room_source_request_outcome_unknown".equals(errorCode);
        }

        public String failureSummary() {
            String code = errorCode == null || errorCode.isBlank()
                ? statusCode > 0 ? "http_" + statusCode : "transport_error"
                : errorCode;
            return message == null || message.isBlank() ? code : code + ": " + message;
        }
    }

    private record RequestEnvelope(
        String path,
        String body,
        boolean get,
        boolean priority,
        boolean controlPlane,
        String requestId,
        String producerEpoch,
        long sequence,
        String sentAt,
        String digest,
        long enqueuedAtMs
    ) {}

    private record PendingRequest(
        String path,
        String body,
        boolean get,
        boolean priority,
        boolean controlPlane,
        long enqueuedAtMs,
        CompletableFuture<IngressResponse> completion
    ) {}

    private record AttemptResult(
        IngressResponse response,
        long elapsedMillis
    ) {}

    private final HelixSensorConfig config;
    private final Logger logger;
    private final HelixSensorRuntimeStatus runtimeStatus;
    private volatile HttpClient client;
    private final Object clientLock = new Object();
    private final AtomicLong transportGeneration = new AtomicLong(1L);
    private final Runnable terminalPauseHandler;
    private final UnaryOperator<String> endpointResolver;
    private final boolean roomIngressEndpoint;
    private final String roomIngressBindingId;
    private final String producerEpoch = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicBoolean pausedForAuth = new AtomicBoolean(false);
    private final AtomicBoolean terminallyPaused = new AtomicBoolean(false);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final Object queueLock = new Object();
    private final ArrayDeque<PendingRequest> controlPlaneRequests = new ArrayDeque<>();
    private final ArrayDeque<PendingRequest> priorityRequests = new ArrayDeque<>();
    private final ArrayDeque<PendingRequest> ordinaryRequests = new ArrayDeque<>();
    private boolean requestInFlight;
    private int queuedRequestCount;
    private volatile long backoffUntilMs = 0L;

    enum RequestLane {
        PRIORITY,
        CONTROL_PLANE,
        ORDINARY,
        NONE
    }

    static RequestLane nextRequestLane(
        boolean priorityWaiting,
        boolean controlPlaneWaiting,
        boolean ordinaryWaiting
    ) {
        if (priorityWaiting) return RequestLane.PRIORITY;
        if (controlPlaneWaiting) return RequestLane.CONTROL_PLANE;
        if (ordinaryWaiting) return RequestLane.ORDINARY;
        return RequestLane.NONE;
    }

    static boolean retryMayContinue(
        boolean priority,
        boolean controlPlane,
        boolean priorityWaiting
    ) {
        // Probe polling/results are demand-driven and retain their bounded
        // same-identity retry. Periodic manifests and heartbeats yield their
        // retry slot as soon as an on-demand probe is queued.
        return (priority && !controlPlane) || !priorityWaiting;
    }

    public HelixHttpClient(
        HelixSensorConfig config,
        Logger logger,
        HelixSensorRuntimeStatus runtimeStatus
    ) {
        this(config, logger, runtimeStatus, () -> {}, UnaryOperator.identity());
    }

    public HelixHttpClient(
        HelixSensorConfig config,
        Logger logger,
        HelixSensorRuntimeStatus runtimeStatus,
        Runnable terminalPauseHandler
    ) {
        this(
            config,
            logger,
            runtimeStatus,
            terminalPauseHandler,
            UnaryOperator.identity()
        );
    }

    public HelixHttpClient(
        HelixSensorConfig config,
        Logger logger,
        HelixSensorRuntimeStatus runtimeStatus,
        Runnable terminalPauseHandler,
        UnaryOperator<String> endpointResolver
    ) {
        this.config = config;
        this.logger = logger;
        this.runtimeStatus = runtimeStatus;
        this.terminalPauseHandler = terminalPauseHandler == null ? () -> {} : terminalPauseHandler;
        this.endpointResolver = endpointResolver == null
            ? UnaryOperator.identity()
            : endpointResolver;
        this.roomIngressEndpoint = isRoomIngressEndpoint(config.endpoint());
        this.roomIngressBindingId = roomIngressEndpoint
            ? roomIngressBindingId(config.endpoint())
            : null;
        this.client = newHttpClient();
    }

    /**
     * Identifies this exact connector process epoch across every lane it
     * publishes. Command catalogs must use the same epoch as source manifests
     * and observations so subject bindings are checked against one connector
     * identity rather than an unrelated command-lane nonce.
     */
    public String producerEpochRef() {
        return producerEpoch;
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            // Room ingress is served by ordinary HTTP/1.1 application servers.
            // Java's default cleartext HTTP/2 upgrade can stall against some
            // Express/Vite deployments before the request reaches the route.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    public CompletableFuture<IngressResponse> postJsonAsync(String path, String json) {
        return enqueue(path, json, false);
    }

    public CompletableFuture<String> getJsonAsync(String path) {
        return enqueue(path, "", true)
            .thenApply(response -> response.success() ? response.body() : "");
    }

    public CompletableFuture<IngressResponse> postWorldEventBatchAsync(String json) {
        return postJsonAsync(route(
            "/world-events/batch",
            "/api/agi/situation/world-event/batch"
        ), json);
    }

    public CompletableFuture<IngressResponse> postManifestAsync(String json) {
        return enqueue(
            route(
                "/manifest",
                "/api/agi/environment/sources/manifest"
            ),
            json,
            false,
            true,
            true
        );
    }

    public CompletableFuture<IngressResponse> postHeartbeatAsync(String json) {
        return enqueue(
            route(
                "/heartbeat",
                "/api/agi/environment/sources/heartbeat"
            ),
            json,
            false,
            true,
            true
        );
    }

    public CompletableFuture<String> getPendingProbesAsync() {
        String source = URLEncoder.encode(config.sourceId(), StandardCharsets.UTF_8);
        return enqueue(
            route(
                "/probes/pending?limit=" + config.maxPendingProbesPerPoll(),
                "/api/agi/environment/sources/" + source + "/probes/pending?limit=" + config.maxPendingProbesPerPoll()
            ),
            "",
            true,
            true
        ).thenApply(response -> response.success() ? response.body() : "");
    }

    public CompletableFuture<IngressResponse> postProbeResultAsync(String json) {
        String source = URLEncoder.encode(config.sourceId(), StandardCharsets.UTF_8);
        return enqueue(
            route(
                "/probes/result",
                "/api/agi/environment/sources/" + source + "/probes/result"
            ),
            json,
            false,
            true
        );
    }

    public boolean degraded() {
        return failureCount.get() > 0 || inBackoff() || terminallyPaused.get();
    }

    public boolean pausedForAuth() {
        return pausedForAuth.get();
    }

    public boolean terminallyPaused() {
        return terminallyPaused.get();
    }

    private CompletableFuture<IngressResponse> enqueue(String path, String body, boolean get) {
        return enqueue(path, body, get, false, false);
    }

    private CompletableFuture<IngressResponse> enqueue(
        String path,
        String body,
        boolean get,
        boolean priority
    ) {
        return enqueue(path, body, get, priority, false);
    }

    private CompletableFuture<IngressResponse> enqueue(
        String path,
        String body,
        boolean get,
        boolean priority,
        boolean controlPlane
    ) {
        synchronized (queueLock) {
            PendingRequest pending = new PendingRequest(
                path,
                body,
                get,
                priority,
                controlPlane,
                System.currentTimeMillis(),
                new CompletableFuture<>()
            );
            if (queuedRequestCount >= MAX_QUEUED_REQUESTS) {
                PendingRequest displaced = controlPlane
                    ? ordinaryRequests.pollLast()
                    : priority
                        ? ordinaryRequests.pollLast()
                        : null;
                if (displaced == null && controlPlane) {
                    displaced = priorityRequests.pollLast();
                }
                if (displaced == null) {
                    return rejectQueuedRequest(pending);
                }
                queuedRequestCount--;
                IngressResponse rejected = skipped(
                    envelope(displaced),
                    "client_queue_preempted",
                    "Queued telemetry yielded to an on-demand probe request."
                );
                displaced.completion().complete(rejected);
            }
            queuedRequestCount++;
            if (controlPlane) {
                // A manifest re-establishes the admission contract and must run
                // before an already queued heartbeat. Heartbeats otherwise retain
                // FIFO order while staying ahead of probe polling and telemetry.
                if (path.endsWith("/manifest")) {
                    controlPlaneRequests.addFirst(pending);
                } else {
                    controlPlaneRequests.addLast(pending);
                }
            } else {
                (priority ? priorityRequests : ordinaryRequests).addLast(pending);
            }
            pumpLocked();
            return pending.completion();
        }
    }

    private CompletableFuture<IngressResponse> rejectQueuedRequest(
        PendingRequest pending
    ) {
        RequestEnvelope envelope = envelope(pending);
        IngressResponse rejected = skipped(
            envelope,
            "client_queue_full",
            "The bounded sensor request queue is full; stale work was not enqueued."
        );
        runtimeStatus.recordIngressReceipt(
            rejected.errorCode(),
            rejected.message(),
            rejected.requestId(),
            rejected.sequence()
        );
        logger.warning(
            "Helix sensor request queue is full; rejected " + pending.path() +
            " request=" + rejected.requestId() +
            " sequence=" + rejected.sequence()
        );
        return CompletableFuture.completedFuture(rejected);
    }

    private void pumpLocked() {
        if (requestInFlight) return;
        PendingRequest pending = switch (nextRequestLane(
            !priorityRequests.isEmpty(),
            !controlPlaneRequests.isEmpty(),
            !ordinaryRequests.isEmpty()
        )) {
            case PRIORITY -> priorityRequests.pollFirst();
            case CONTROL_PLANE -> controlPlaneRequests.pollFirst();
            case ORDINARY -> ordinaryRequests.pollFirst();
            case NONE -> null;
        };
        if (pending == null) return;

        requestInFlight = true;
        RequestEnvelope envelope = envelope(pending);
        PendingRequest active = pending;
        sendWithRetry(envelope, 1).whenComplete((response, error) -> {
            if (error == null) {
                active.completion().complete(response);
            } else {
                active.completion().completeExceptionally(error);
            }
            synchronized (queueLock) {
                queuedRequestCount = Math.max(0, queuedRequestCount - 1);
                requestInFlight = false;
                pumpLocked();
            }
        });
    }

    private RequestEnvelope envelope(PendingRequest pending) {
        return new RequestEnvelope(
            pending.path(),
            pending.body(),
            pending.get(),
            pending.priority(),
            pending.controlPlane(),
            UUID.randomUUID().toString(),
            producerEpoch,
            sequence.incrementAndGet(),
            Instant.now().toString(),
            sha256Digest(pending.body()),
            pending.enqueuedAtMs()
        );
    }

    private CompletableFuture<IngressResponse> sendWithRetry(
        RequestEnvelope envelope,
        int attempt
    ) {
        if (terminallyPaused.get()) {
            return CompletableFuture.completedFuture(skipped(
                envelope,
                "client_terminally_paused",
                "The sensor transport is paused after a terminal credential or binding response."
            ));
        }
        if (
            attempt == 1 &&
            inBackoff() &&
            !mayBypassBackoff(envelope)
        ) {
            return CompletableFuture.completedFuture(skipped(
                envelope,
                "client_backoff",
                "The sensor transport is waiting for its retry backoff."
            ));
        }
        if (
            attempt == 1 &&
            System.currentTimeMillis() - envelope.enqueuedAtMs() > MAX_QUEUED_AGE_MS
        ) {
            return CompletableFuture.completedFuture(skipped(
                envelope,
                "client_request_stale",
                "The queued sensor request expired before delivery and was discarded."
            ));
        }

        HttpRequest.Builder builder = requestBuilder(envelope);
        if (envelope.get()) {
            builder.GET();
        } else {
            builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    envelope.body(),
                    StandardCharsets.UTF_8
                ));
        }
        long started = System.nanoTime();
        HttpClient attemptClient = client;
        return attemptClient.sendAsync(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
            .handle((response, error) -> {
                IngressResponse ingressResponse;
                if (error != null) {
                    replaceTransport(attemptClient);
                    ingressResponse = new IngressResponse(
                        0,
                        "",
                        envelope.requestId(),
                        envelope.sequence(),
                        "transport_error",
                        rootMessage(error),
                        false,
                        false,
                        false,
                        false
                    );
                } else {
                    ingressResponse = parseResponse(
                        envelope,
                        response.statusCode(),
                        response.body()
                    );
                }
                return new AttemptResult(
                    ingressResponse,
                    elapsedMillis(started)
                );
            })
            .thenCompose(attemptResult -> {
                IngressResponse response = attemptResult.response();
                if (
                    attempt < MAX_SAME_IDENTITY_ATTEMPTS &&
                    retrySameIdentity(response) &&
                    retryMayContinue(
                        envelope.priority(),
                        envelope.controlPlane(),
                        priorityRequestWaiting()
                    )
                ) {
                    long delayMs = Math.min(2_000L, 250L << (attempt - 1));
                    return CompletableFuture
                        .runAsync(
                            () -> {},
                            CompletableFuture.delayedExecutor(
                                delayMs,
                                TimeUnit.MILLISECONDS
                            )
                        )
                        .thenCompose(ignored ->
                            sendWithRetry(envelope, attempt + 1)
                        );
                }
                handleStatus(
                    envelope.path(),
                    response,
                    attemptResult.elapsedMillis()
                );
                return CompletableFuture.completedFuture(response);
            });
    }

    private void replaceTransport(HttpClient failedClient) {
        synchronized (clientLock) {
            if (client != failedClient) return;
            client = newHttpClient();
            transportGeneration.incrementAndGet();
            // Graceful shutdown prevents a poisoned pooled channel from being
            // reused while allowing any already-completing callback to settle.
            failedClient.shutdown();
        }
    }

    long transportGenerationForTest() {
        return transportGeneration.get();
    }

    private static boolean mayBypassBackoff(RequestEnvelope envelope) {
        if (!envelope.priority()) return false;
        int query = envelope.path().indexOf('?');
        String routePath = query < 0
            ? envelope.path()
            : envelope.path().substring(0, query);
        return !routePath.endsWith("/manifest");
    }

    private boolean priorityRequestWaiting() {
        synchronized (queueLock) {
            return !priorityRequests.isEmpty();
        }
    }

    private HttpRequest.Builder requestBuilder(RequestEnvelope envelope) {
        String endpoint = endpointResolver.apply(config.endpoint());
        if (endpoint == null || endpoint.isBlank()) endpoint = config.endpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(HelixSensorConfig.stripTrailingSlash(endpoint) + envelope.path()))
            // Local durable admission may need to flush the complete desktop
            // state snapshot before it can return the signed receipt. Keep the
            // fast telemetry/action timeout for ordinary traffic, but do not
            // abandon a manifest or heartbeat while that bounded commit is
            // still finishing; an early same-identity retry can only observe
            // the valid request as temporarily in progress.
            .timeout(Duration.ofSeconds(requestTimeoutSecondsFor(
                envelope.controlPlane()
            )))
            .header("Accept", "application/json")
            .header("X-Helix-Ingress-Version", "1")
            .header("X-Helix-Request-Id", envelope.requestId())
            .header("X-Helix-Producer-Epoch", envelope.producerEpoch())
            .header("X-Helix-Sequence", Long.toString(envelope.sequence()))
            .header("X-Helix-Sent-At", envelope.sentAt())
            .header("Digest", envelope.digest());
        if (config.bearerToken() != null && !config.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.bearerToken());
        }
        return builder;
    }

    static long requestTimeoutSecondsFor(boolean controlPlane) {
        return controlPlane
            ? CONTROL_PLANE_REQUEST_TIMEOUT_SECONDS
            : ORDINARY_REQUEST_TIMEOUT_SECONDS;
    }

    private IngressResponse parseResponse(
        RequestEnvelope envelope,
        int status,
        String body
    ) {
        if (!roomIngressEndpoint) {
            boolean ok = status >= 200 && status < 300;
            return new IngressResponse(
                status,
                body == null ? "" : body,
                envelope.requestId(),
                envelope.sequence(),
                ok ? null : "http_" + status,
                "",
                ok,
                ok,
                false,
                true
            );
        }
        try {
            Map<String, Object> receipt = HelixJson.asObject(
                HelixJson.parse(body == null ? "" : body)
            );
            if (receipt.isEmpty()) {
                return invalidReceipt(envelope, status, body);
            }
            if (status >= 200 && status < 300) {
                String validationError = validateSuccessfulRoomIngressReceipt(
                    envelope,
                    receipt
                );
                if (validationError != null) {
                    return invalidReceipt(
                        envelope,
                        status,
                        body,
                        "The room ingress response failed typed receipt validation: " +
                        validationError
                    );
                }
            }
            boolean ok = Boolean.TRUE.equals(receipt.get("ok"));
            boolean accepted = Boolean.TRUE.equals(receipt.get("accepted"));
            boolean replayed = Boolean.TRUE.equals(receipt.get("replayed"));
            String error = stringOrNull(receipt.get("error"));
            String message = stringOrNull(receipt.get("message"));
            return new IngressResponse(
                status,
                body == null ? "" : body,
                envelope.requestId(),
                envelope.sequence(),
                error,
                message,
                ok,
                accepted,
                replayed,
                true
            );
        } catch (RuntimeException error) {
            return invalidReceipt(envelope, status, body);
        }
    }

    private IngressResponse invalidReceipt(
        RequestEnvelope envelope,
        int status,
        String body
    ) {
        return new IngressResponse(
            status,
            body == null ? "" : body,
            envelope.requestId(),
            envelope.sequence(),
            "invalid_ingress_receipt",
            "The room ingress response was not a typed JSON receipt.",
            false,
            false,
            false,
            true
        );
    }

    private IngressResponse invalidReceipt(
        RequestEnvelope envelope,
        int status,
        String body,
        String message
    ) {
        return new IngressResponse(
            status,
            body == null ? "" : body,
            envelope.requestId(),
            envelope.sequence(),
            "invalid_ingress_receipt",
            message,
            false,
            false,
            false,
            true
        );
    }

    private String validateSuccessfulRoomIngressReceipt(
        RequestEnvelope envelope,
        Map<String, Object> receipt
    ) {
        if (!"helix.room_source_ingress_receipt.v1".equals(receipt.get("schema"))) {
            return "schema";
        }
        if (!Boolean.TRUE.equals(receipt.get("ok"))) return "ok";
        if (!Boolean.TRUE.equals(receipt.get("accepted"))) return "accepted";
        if (!(receipt.get("replayed") instanceof Boolean)) return "replayed";
        if (
            roomIngressBindingId == null ||
            !requestProjectionId(
                roomIngressBindingId,
                envelope.requestId()
            ).equals(receipt.get("request_id"))
        ) {
            return "request_id";
        }
        String expectedKind = expectedKind(envelope.path());
        if (expectedKind == null || !expectedKind.equals(receipt.get("kind"))) {
            return "kind";
        }
        if (
            roomIngressBindingId == null ||
            !roomIngressBindingId.equals(receipt.get("binding_id"))
        ) {
            return "binding_id";
        }
        if (!config.sourceId().equals(receipt.get("source_id"))) {
            return "source_id";
        }
        if (!config.roomId().equals(receipt.get("room_id"))) {
            return "room_id";
        }
        if (!config.worldId().equals(receipt.get("world_id"))) {
            return "world_id";
        }
        if (
            !"source_observation_not_assistant_answer".equals(
                receipt.get("content_role")
            )
        ) {
            return "content_role";
        }
        if (!Boolean.TRUE.equals(receipt.get("reentry_required"))) {
            return "reentry_required";
        }
        if (!Boolean.FALSE.equals(receipt.get("answer_authority"))) {
            return "answer_authority";
        }
        if (!Boolean.FALSE.equals(receipt.get("assistant_answer"))) {
            return "assistant_answer";
        }
        if (!Boolean.FALSE.equals(receipt.get("terminal_eligible"))) {
            return "terminal_eligible";
        }
        if (!Boolean.FALSE.equals(receipt.get("raw_content_included"))) {
            return "raw_content_included";
        }
        if (!(receipt.get("message") instanceof String message) || message.isBlank()) {
            return "message";
        }
        Object error = receipt.get("error");
        if (error != null && !(error instanceof String)) return "error";
        return null;
    }

    private IngressResponse skipped(
        RequestEnvelope envelope,
        String errorCode,
        String message
    ) {
        return new IngressResponse(
            0,
            "",
            envelope.requestId(),
            envelope.sequence(),
            errorCode,
            message,
            false,
            false,
            false,
            false
        );
    }

    private boolean retrySameIdentity(IngressResponse response) {
        if (response.outcomeUnknown()) return false;
        if (!response.delivered()) return true;
        if ("invalid_ingress_receipt".equals(response.errorCode())) return true;
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            return true;
        }
        return "room_source_request_in_progress".equals(response.errorCode());
    }

    private String route(String roomIngressPath, String legacyPath) {
        return roomIngressEndpoint ? roomIngressPath : legacyPath;
    }

    static boolean isRoomIngressEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String path = uri.getPath();
            return path != null
                && path.matches("^/(?:[^/]+/)*api/room-ingress/v1/bindings/[^/]+$")
                && uri.getQuery() == null
                && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String roomIngressBindingId(String endpoint) {
        URI uri = URI.create(endpoint);
        String path = uri.getPath();
        int separator = path == null ? -1 : path.lastIndexOf('/');
        return separator < 0 || separator == path.length() - 1
            ? null
            : path.substring(separator + 1);
    }

    static String requestProjectionId(String bindingId, String requestId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bindingId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(
                requestId.getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return "request_sha256:" + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static String expectedKind(String path) {
        int query = path.indexOf('?');
        String routePath = query < 0 ? path : path.substring(0, query);
        return switch (routePath) {
            case "/world-events/batch" -> "world_event_batch";
            case "/manifest" -> "manifest";
            case "/heartbeat" -> "heartbeat";
            case "/probes/pending" -> "probe_requests";
            case "/probes/result" -> "probe_result";
            case "/status" -> "status";
            default -> null;
        };
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() || "null".equals(normalized)
            ? null
            : normalized;
    }

    private static String sha256Digest(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8));
            return "sha-256=" + Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void handleStatus(
        String path,
        IngressResponse response,
        long elapsedMillis
    ) {
        runtimeStatus.recordHttpResult(
            path,
            response.statusCode(),
            elapsedMillis,
            response.success()
        );
        if (response.success()) {
            failureCount.set(0);
            backoffUntilMs = 0L;
            return;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            pausedForAuth.set(true);
            pauseTerminal(
                "auth_error",
                response,
                "Helix authorization failed for " + path + "; stopping sensor loops."
            );
            return;
        }
        if (response.statusCode() == 410) {
            pauseTerminal(
                "binding_inactive",
                response,
                "Helix room source binding is inactive for " + path + "; stopping sensor loops."
            );
            return;
        }
        if (!response.delivered()) {
            markBackoff(
                "timeout/error for " + path + ": " + response.failureSummary()
            );
            return;
        }
        runtimeStatus.recordIngressReceipt(
            response.errorCode(),
            response.message(),
            response.requestId(),
            response.sequence()
        );
        if (response.statusCode() == 413) {
            markBackoff(
                "Helix payload too large for " + path + "; snapshots will be truncated/skipped."
            );
            return;
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            markBackoff("Helix " + response.failureSummary() + " for " + path);
            return;
        }
        if ("room_source_request_in_progress".equals(response.errorCode())) {
            markBackoff(
                "Helix " + response.failureSummary() + " for " + path
            );
            return;
        }
        logger.warning(
            "Helix ingress rejected " + path +
            " request=" + response.requestId() +
            " sequence=" + response.sequence() +
            " " + response.failureSummary()
        );
    }

    private void pauseTerminal(
        String state,
        IngressResponse response,
        String logMessage
    ) {
        runtimeStatus.recordBackoff(state, response.failureSummary());
        runtimeStatus.recordIngressReceipt(
            response.errorCode(),
            response.message(),
            response.requestId(),
            response.sequence()
        );
        logger.warning(
            logMessage +
            " request=" + response.requestId() +
            " sequence=" + response.sequence() +
            " " + response.failureSummary()
        );
        if (terminallyPaused.compareAndSet(false, true)) {
            try {
                terminalPauseHandler.run();
            } catch (RuntimeException callbackError) {
                logger.warning(
                    "Could not stop Helix sensor loops: " + callbackError.getMessage()
                );
            }
        }
    }

    private boolean inBackoff() {
        return System.currentTimeMillis() < backoffUntilMs;
    }

    private void markBackoff(String message) {
        int failures = Math.min(6, failureCount.incrementAndGet());
        long delayMs = Math.min(60_000L, (1L << failures) * 1000L);
        backoffUntilMs = System.currentTimeMillis() + delayMs;
        runtimeStatus.recordBackoff("backoff", message);
        logger.warning(message + " Backing off for " + delayMs + "ms.");
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank()
            ? cursor.getClass().getSimpleName()
            : message;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    @Override
    public void close() {
        terminallyPaused.set(true);
        synchronized (clientLock) {
            client.shutdownNow();
        }
    }
}
