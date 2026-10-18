package com.casimirbot.helixsensor.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class FabricConnectorCommands {
    private FabricConnectorCommands() {}

    static void register(HelixFabricSensorMod controller) {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                dispatcher.register(
                    Commands.literal("helix")
                        .requires(source ->
                            source.hasPermission(Commands.LEVEL_GAMEMASTERS)
                        )
                        .then(
                            Commands.literal("pair")
                                .then(
                                    Commands.argument(
                                        "code",
                                        StringArgumentType.word()
                                    ).executes(context -> {
                                        String code = StringArgumentType.getString(
                                            context,
                                            "code"
                                        );
                                        context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                "Helix is redeeming the one-time pairing code..."
                                            ),
                                            false
                                        );
                                        controller.pairAsync(
                                            context.getSource(),
                                            code
                                        );
                                        return 1;
                                    })
                                )
                        )
                        .then(
                            Commands.literal("status").executes(context -> {
                                controller.sendStatus(context.getSource());
                                return 1;
                            })
                        )
                        .then(
                            Commands.literal("reconnect").executes(context -> {
                                controller.reconnect(context.getSource());
                                return 1;
                            })
                        )
                        .then(
                            Commands.literal("capabilities").executes(context -> {
                                controller.sendCapabilities(context.getSource());
                                return 1;
                            })
                        )
                        .then(
                            Commands.literal("unpair")
                                .then(
                                    Commands.literal("confirm").executes(context -> {
                                        context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                "Helix is revoking this connector binding..."
                                            ),
                                            false
                                        );
                                        controller.unpairAsync(context.getSource());
                                        return 1;
                                    })
                                )
                        )
                )
        );
    }

    static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    static void failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }
}
