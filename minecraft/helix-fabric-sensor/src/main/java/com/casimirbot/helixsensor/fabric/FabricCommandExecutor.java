package com.casimirbot.helixsensor.fabric;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.casimirbot.helixsensor.HelixJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class FabricCommandExecutor {
    static final String CONNECTOR_MANAGEMENT_OUTCOME =
        "connector_management_forbidden";
    private static final int MAX_OUTPUT_LINES = 256;
    private static final int MAX_OUTPUT_BYTES = 64_000;

    private final MinecraftServer server;
    private final FabricCommandConfig config;
    private final String activeCatalogId;

    private record SourceResolution(
        CommandSourceStack source,
        String rejectedOutcome
    ) {}

    FabricCommandExecutor(
        MinecraftServer server,
        FabricCommandConfig config,
        String activeCatalogId
    ) {
        this.server = server;
        this.config = config;
        this.activeCatalogId = activeCatalogId;
    }

    Map<String, Object> execute(Map<String, Object> request) {
        String requestId = text(request, "command_request_id");
        String command = text(request, "command_text").replaceFirst("^/+", "");
        String requestHash = text(request, "command_hash");
        String requestedCategory = text(request, "requested_category");
        String expectedEffect = text(request, "expected_effect");
        String authorityProfile = text(request, "authority_profile");
        if (!exactIdentity(request) || command.isBlank() || command.contains("\n") || command.contains("\r")) {
            return rejection(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                "wrong_environment",
                "The command request did not match this exact room, source, world, and authority.",
                false
            );
        }
        if (!FabricCommandCatalogBuilder.sha256(command).equals(requestHash)) {
            return rejection(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                "command_parse_failed",
                "The command hash did not match its exact command text.",
                false
            );
        }
        if (FabricCommandCatalogBuilder.isConnectorManagementRoot(root(command))) {
            return rejection(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                CONNECTOR_MANAGEMENT_OUTCOME,
                "Connector management commands are human-only and are never executable through the runtime agent.",
                false
            );
        }
        if (deadlinePassed(request)) {
            return rejection(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                "command_timeout",
                "The command request expired before live dispatcher parsing.",
                false
            );
        }

        if (!FabricCommandAuthorityPolicy.profileAllows(
            authorityProfile,
            requestedCategory,
            expectedEffect
        )) {
            return rejection(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                "permission_revoked",
                "The command exceeds the effective room authority profile.",
                false
            );
        }

        CapturingSource capture = new CapturingSource();
        AtomicBoolean callbackSuccess = new AtomicBoolean(false);
        AtomicInteger resultCode = new AtomicInteger(0);
        SourceResolution sourceResolution = sourceForRequest(
            request,
            command,
            authorityProfile,
            requestedCategory,
            capture
        );
        if (sourceResolution.source() == null) {
            return result(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                sourceResolution.rejectedOutcome(),
                0,
                capture,
                false,
                false
            );
        }
        CommandSourceStack source = sourceResolution.source()
            .withPermission(Commands.LEVEL_OWNERS)
            .withSource(capture)
            .withCallback((success, result) -> {
                callbackSuccess.set(success);
                resultCode.set(result);
            });
        ParseResults<CommandSourceStack> parsed = server
            .getCommands()
            .getDispatcher()
            .parse(command, source);
        try {
            Commands.validateParseResults(parsed);
        } catch (CommandSyntaxException error) {
            capture.add(error.getMessage());
            return result(
                requestId,
                requestHash,
                root(command),
                requestedCategory,
                expectedEffect,
                "command_parse_failed",
                0,
                capture,
                false,
                false
            );
        }

        FabricCommandClassifier.Classification actual =
            FabricCommandClassifier.classify(command);
        if (
            !actual.category().equals(requestedCategory) ||
            !actual.effectClass().equals(expectedEffect)
        ) {
            capture.add(
                "The live dispatcher classified this as " +
                actual.category() + "/" + actual.effectClass() +
                ", which does not match the authorized request."
            );
            return result(
                requestId,
                requestHash,
                root(command),
                actual.category(),
                actual.effectClass(),
                "command_category_mismatch",
                0,
                capture,
                false,
                true
            );
        }

        boolean dispatched = false;
        try {
            dispatched = true;
            server.getCommands().performCommand(parsed, command);
        } catch (RuntimeException error) {
            capture.add("Minecraft rejected the command during execution.");
        }
        boolean succeeded = dispatched && callbackSuccess.get();
        return result(
            requestId,
            requestHash,
            root(command),
            actual.category(),
            actual.effectClass(),
            succeeded ? "succeeded" : "failed",
            resultCode.get(),
            capture,
            dispatched && !"read_only".equals(actual.effectClass()),
            true
        );
    }

    private boolean exactIdentity(Map<String, Object> request) {
        return config.commandAuthorityId().equals(text(request, "command_authority_id"))
            && config.environmentBindingId().equals(text(request, "environment_binding_id"))
            && config.roomId().equals(text(request, "room_id"))
            && config.sourceId().equals(text(request, "source_id"))
            && config.worldId().equals(text(request, "world_id"))
            && activeCatalogId.equals(text(request, "command_catalog_id"))
            && config.policyVersion() == integer(request, "policy_version");
    }

    private SourceResolution sourceForRequest(
        Map<String, Object> request,
        String command,
        String authorityProfile,
        String requestedCategory,
        CapturingSource capture
    ) {
        String subjectNativeId = text(request, "subject_native_id");
        boolean restrictedPlayerCommand =
            FabricCommandAuthorityPolicy.playerSubjectRequired(
                authorityProfile,
                requestedCategory
            );
        if (!FabricCommandAuthorityPolicy.selectedPlayerSourceRequired(
            authorityProfile,
            requestedCategory,
            command,
            !subjectNativeId.isBlank()
        )) {
            return new SourceResolution(
                server.createCommandSourceStack(),
                null
            );
        }
        ServerPlayer selected = server.getPlayerList().getPlayers().stream()
            .filter(player ->
                player.getUUID().toString().equalsIgnoreCase(subjectNativeId)
            )
            .findFirst()
            .orElse(null);
        if (selected == null) {
            capture.add(
                "The room member's selected Minecraft player is no longer online."
            );
            return new SourceResolution(null, "subject_binding_stale");
        }
        List<String> onlineNames = server.getPlayerList().getPlayers().stream()
            .map(player -> player.getGameProfile().getName())
            .toList();
        if (restrictedPlayerCommand && !FabricCommandAuthorityPolicy.confinedToSelectedPlayer(
            command,
            selected.getGameProfile().getName(),
            onlineNames
        )) {
            capture.add(
                "The restricted player command targets someone other than the room member's selected player."
            );
            return new SourceResolution(null, "permission_revoked");
        }
        return new SourceResolution(selected.createCommandSourceStack(), null);
    }

    private static boolean deadlinePassed(Map<String, Object> request) {
        try {
            return !Instant.parse(text(request, "deadline_at")).isAfter(Instant.now());
        } catch (RuntimeException error) {
            return true;
        }
    }

    private static Map<String, Object> rejection(
        String requestId,
        String commandHash,
        String root,
        String category,
        String effect,
        String outcome,
        String summary,
        boolean parsed
    ) {
        CapturingSource capture = new CapturingSource();
        capture.add(summary);
        return result(
            requestId,
            commandHash,
            root,
            category,
            effect,
            outcome,
            0,
            capture,
            false,
            parsed
        );
    }

    private static Map<String, Object> result(
        String requestId,
        String commandHash,
        String root,
        String category,
        String effect,
        String outcome,
        int resultCode,
        CapturingSource capture,
        boolean sideEffectsPossible,
        boolean parsed
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "helix.environment_command.result.v1");
        result.put("command_request_id", requestId);
        result.put("command_execution_id", "command_execution:" + UUID.randomUUID());
        result.put("command_hash", commandHash);
        result.put("command_root", root.isBlank() ? "unknown" : root);
        result.put("parsed_category", validCategory(category) ? category : "mod_command");
        result.put("effect_class", validEffect(effect) ? effect : "unknown");
        result.put("outcome", outcome);
        result.put("result_code", resultCode);
        result.put("summary", capture.summary(outcome));
        result.put("output_lines", capture.lines());
        result.put("output_truncated", capture.truncated());
        result.put("affected_count", resultCode >= 0 ? resultCode : null);
        result.put("side_effects_performed", sideEffectsPossible);
        result.put(
            "environment_mutation_performed",
            sideEffectsPossible && !"server_administration".equals(effect)
        );
        result.put(
            "server_administration_performed",
            sideEffectsPossible && "server_administration".equals(effect)
        );
        result.put("parsed_by_live_dispatcher", parsed);
        result.put("host_access_performed", false);
        result.put("automatic_retry_performed", false);
        result.put("model_invoked", false);
        result.put("created_at", Instant.now().toString());
        result.put("assistant_answer", false);
        result.put("raw_content_included", false);
        return result;
    }

    private static String text(Map<String, Object> value, String key) {
        Object entry = value.get(key);
        return entry instanceof String text ? text.trim() : "";
    }

    private static int integer(Map<String, Object> value, String key) {
        Object entry = value.get(key);
        return entry instanceof Number number ? number.intValue() : -1;
    }

    private static String root(String command) {
        String normalized = command == null ? "" : command.trim().replaceFirst("^/+", "");
        return normalized.isBlank() ? "unknown" : normalized.split("\\s+", 2)[0];
    }

    private static boolean validCategory(String value) {
        return List.of(
            "query", "player_state", "player_inventory", "player_movement",
            "world_time_weather", "world_build", "entity_control",
            "server_administration", "mod_command"
        ).contains(value);
    }

    private static boolean validEffect(String value) {
        return List.of(
            "read_only", "player_mutation", "world_mutation",
            "server_administration", "unknown"
        ).contains(value);
    }

    private static final class CapturingSource implements CommandSource {
        private final List<String> lines = new ArrayList<>();
        private int bytes;
        private boolean truncated;

        @Override
        public void sendSystemMessage(Component message) {
            add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        void add(String line) {
            String bounded = line == null ? "" : line.trim();
            int next = bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (
                lines.size() >= MAX_OUTPUT_LINES ||
                bytes + next > MAX_OUTPUT_BYTES
            ) {
                truncated = true;
                return;
            }
            if (!bounded.isBlank()) {
                lines.add(bounded.length() > 2_000 ? bounded.substring(0, 2_000) : bounded);
                bytes += next;
            }
        }

        List<String> lines() {
            return List.copyOf(lines);
        }

        boolean truncated() {
            return truncated;
        }

        String summary(String outcome) {
            return lines.isEmpty()
                ? "Minecraft command " + outcome.replace('_', ' ') + "."
                : lines.get(lines.size() - 1);
        }
    }
}
