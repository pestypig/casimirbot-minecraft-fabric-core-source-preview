package com.casimirbot.helixsensor.fabric;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.casimirbot.helixsensor.HelixJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

final class FabricCommandCatalogBuilder {
    private static final int MAX_NODES = 512;

    private FabricCommandCatalogBuilder() {}

    static Map<String, Object> build(
        MinecraftServer server,
        FabricCommandConfig config,
        String producerEpochRef
    ) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<CommandNode<CommandSourceStack>> roots = new ArrayList<>(
            server.getCommands().getDispatcher().getRoot().getChildren()
        );
        roots.removeIf(root -> isConnectorManagementRoot(root.getName()));
        roots.sort(Comparator.comparing(CommandNode::getName));
        // Preserve every root command before spending the bounded page on
        // deeper syntax. This keeps vanilla and mod roots discoverable even
        // when a large modpack has a command tree wider than one page.
        for (CommandNode<CommandSourceStack> root : roots) {
            appendNode(nodes, root, root.getName());
            if (nodes.size() >= MAX_NODES) break;
        }
        List<CommandNode<CommandSourceStack>> detailRoots = new ArrayList<>(roots);
        detailRoots.sort(
            Comparator.comparingInt((CommandNode<CommandSourceStack> root) ->
                FabricGameplayCommands.ROOT.equals(root.getName()) ? 0 : 1
            ).thenComparing(CommandNode::getName)
        );
        for (CommandNode<CommandSourceStack> root : detailRoots) {
            appendChildren(nodes, root, root.getName());
            if (nodes.size() >= MAX_NODES) break;
        }
        String canonical = HelixJson.stringify(nodes);
        String treeHash = sha256(canonical);
        String now = Instant.now().toString();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("schema", "helix.environment_command.catalog_page.v1");
        page.put(
            "command_catalog_id",
            "command_catalog:" + UUID.randomUUID()
        );
        page.put("command_tree_hash", treeHash);
        page.put("environment_binding_id", config.environmentBindingId());
        page.put("source_id", config.sourceId());
        page.put("world_id", config.worldId());
        page.put("adapter_profile_id", config.adapterProfileId());
        page.put("domain_adapter", config.domainAdapter());
        page.put("game_version", server.getServerVersion());
        page.put("producer_epoch_ref", producerEpochRef);
        page.put("root_command_count", roots.size());
        page.put("path_prefix", "");
        page.put("nodes", nodes);
        page.put("next_cursor", null);
        page.put("generated_at", now);
        page.put(
            "expires_at",
            config.expiresAt().isBlank() ? null : config.expiresAt()
        );
        page.put("raw_dispatcher_tree_included", false);
        page.put(
            "content_role",
            "environment_command_catalog_not_assistant_answer"
        );
        page.put("answer_authority", false);
        page.put("assistant_answer", false);
        page.put("terminal_eligible", false);
        page.put("raw_content_included", false);
        return page;
    }

    private static void appendNode(
        List<Map<String, Object>> nodes,
        CommandNode<CommandSourceStack> node,
        String path
    ) {
        if (nodes.size() >= MAX_NODES) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put(
            "node_kind",
            node instanceof LiteralCommandNode<?> ? "literal" : "argument"
        );
        item.put("executable", node.getCommand() != null);
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            item.put("argument_type", argument.getType().getClass().getName());
            item.put(
                "suggestion_provider",
                argument.getCustomSuggestions() == null
                    ? null
                    : argument.getCustomSuggestions().getClass().getName()
            );
        } else {
            item.put("argument_type", null);
            item.put("suggestion_provider", null);
        }
        item.put(
            "redirects_to",
            node.getRedirect() == null ? null : node.getRedirect().getName()
        );
        item.put("child_count", node.getChildren().size());
        nodes.add(item);
    }

    private static void appendChildren(
        List<Map<String, Object>> nodes,
        CommandNode<CommandSourceStack> node,
        String path
    ) {
        if (nodes.size() >= MAX_NODES) return;
        List<CommandNode<CommandSourceStack>> children = new ArrayList<>(
            node.getChildren()
        );
        children.sort(Comparator.comparing(CommandNode::getName));
        for (CommandNode<CommandSourceStack> child : children) {
            String childPath = path + " " + usage(child);
            appendNode(nodes, child, childPath);
            appendChildren(nodes, child, childPath);
            if (nodes.size() >= MAX_NODES) return;
        }
    }

    private static String usage(CommandNode<CommandSourceStack> node) {
        return node instanceof LiteralCommandNode<?>
            ? node.getName()
            : "<" + node.getName() + ">";
    }

    static boolean isConnectorManagementRoot(String root) {
        if (root == null) return false;
        String normalized = root.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("helix") || normalized.endsWith(":helix");
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
            );
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
