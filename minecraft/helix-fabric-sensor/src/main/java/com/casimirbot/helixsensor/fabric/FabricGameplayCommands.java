package com.casimirbot.helixsensor.fabric;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gameplay-facing, catalog-visible agency primitives. These commands remain
 * independent of human-only /helix connector management and execute only
 * through Minecraft's live dispatcher and its normal permission checks. The
 * catalog builder prioritizes this small tree so bounded catalogs always show
 * the exact checkpoint and safety syntax before large mod command subtrees.
 */
final class FabricGameplayCommands {
    static final String ROOT = "helixgame";
    static final String PING_RESPONSE = "Helix gameplay command lane is active.";

    private FabricGameplayCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                dispatcher.register(
                    Commands.literal(ROOT)
                        .requires(source ->
                            source.hasPermission(Commands.LEVEL_GAMEMASTERS)
                        )
                        .then(
                            Commands.literal("ping").executes(context -> {
                                context.getSource().sendSuccess(
                                    () -> Component.literal(PING_RESPONSE),
                                    false
                                );
                                return 1;
                            })
                        )
                        .then(
                            Commands.literal("checkpoint")
                                .then(
                                    Commands.literal("capture")
                                        .then(
                                            Commands.argument(
                                                "name",
                                                StringArgumentType.word()
                                            ).then(
                                                Commands.argument(
                                                    "horizontal_radius",
                                                    IntegerArgumentType.integer(
                                                        1,
                                                        FabricRegionCheckpointStore.MAX_HORIZONTAL_RADIUS
                                                    )
                                                ).then(
                                                    Commands.argument(
                                                        "vertical_radius",
                                                        IntegerArgumentType.integer(
                                                            1,
                                                            FabricRegionCheckpointStore.MAX_VERTICAL_RADIUS
                                                        )
                                                    ).executes(context -> {
                                                        ServerPlayer player = context
                                                            .getSource()
                                                            .getPlayerOrException();
                                                        FabricRegionCheckpointStore.Operation operation =
                                                            FabricRegionCheckpointStore.capture(
                                                                player,
                                                                StringArgumentType.getString(
                                                                    context,
                                                                    "name"
                                                                ),
                                                                IntegerArgumentType.getInteger(
                                                                    context,
                                                                    "horizontal_radius"
                                                                ),
                                                                IntegerArgumentType.getInteger(
                                                                    context,
                                                                    "vertical_radius"
                                                                )
                                                            );
                                                        return emit(
                                                            context.getSource(),
                                                            operation.ok(),
                                                            operation.message()
                                                        );
                                                    })
                                                )
                                            )
                                        )
                                )
                                .then(
                                    Commands.literal("capture_box")
                                        .then(
                                            Commands.argument(
                                                "name",
                                                StringArgumentType.word()
                                            ).then(
                                                Commands.argument(
                                                    "x1",
                                                    IntegerArgumentType.integer()
                                                ).then(
                                                    Commands.argument(
                                                        "y1",
                                                        IntegerArgumentType.integer()
                                                    ).then(
                                                        Commands.argument(
                                                            "z1",
                                                            IntegerArgumentType.integer()
                                                        ).then(
                                                            Commands.argument(
                                                                "x2",
                                                                IntegerArgumentType.integer()
                                                            ).then(
                                                                Commands.argument(
                                                                    "y2",
                                                                    IntegerArgumentType.integer()
                                                                ).then(
                                                                    Commands.argument(
                                                                        "z2",
                                                                        IntegerArgumentType.integer()
                                                                    ).executes(context -> {
                                                                        FabricRegionCheckpointStore.Operation operation =
                                                                            FabricRegionCheckpointStore.captureBox(
                                                                                context.getSource().getPlayerOrException(),
                                                                                StringArgumentType.getString(context, "name"),
                                                                                IntegerArgumentType.getInteger(context, "x1"),
                                                                                IntegerArgumentType.getInteger(context, "y1"),
                                                                                IntegerArgumentType.getInteger(context, "z1"),
                                                                                IntegerArgumentType.getInteger(context, "x2"),
                                                                                IntegerArgumentType.getInteger(context, "y2"),
                                                                                IntegerArgumentType.getInteger(context, "z2")
                                                                            );
                                                                        return emit(
                                                                            context.getSource(),
                                                                            operation.ok(),
                                                                            operation.message()
                                                                        );
                                                                    })
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                )
                                .then(
                                    Commands.literal("restore")
                                        .then(
                                            Commands.argument(
                                                "name",
                                                StringArgumentType.word()
                                            ).executes(context -> {
                                                FabricRegionCheckpointStore.Operation operation =
                                                    FabricRegionCheckpointStore.restore(
                                                        context.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(
                                                            context,
                                                            "name"
                                                        )
                                                    );
                                                return emit(
                                                    context.getSource(),
                                                    operation.ok(),
                                                    operation.message()
                                                );
                                            })
                                        )
                                )
                                .then(
                                    Commands.literal("discard")
                                        .then(
                                            Commands.argument(
                                                "name",
                                                StringArgumentType.word()
                                            ).executes(context -> {
                                                FabricRegionCheckpointStore.Operation operation =
                                                    FabricRegionCheckpointStore.discard(
                                                        context.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(
                                                            context,
                                                            "name"
                                                        )
                                                    );
                                                return emit(
                                                    context.getSource(),
                                                    operation.ok(),
                                                    operation.message()
                                                );
                                            })
                                        )
                                )
                                .then(
                                    Commands.literal("status").executes(context -> {
                                        FabricRegionCheckpointStore.Operation operation =
                                            FabricRegionCheckpointStore.status(
                                                context.getSource().getPlayerOrException()
                                            );
                                        return emit(
                                            context.getSource(),
                                            operation.ok(),
                                            operation.message()
                                        );
                                    })
                                )
                        )
                        .then(
                            Commands.literal("fall_rescue")
                                .then(
                                    Commands.literal("arm")
                                        .then(
                                            Commands.argument(
                                                "seconds",
                                                IntegerArgumentType.integer(
                                                    FabricFallRescueController.MIN_SECONDS,
                                                    FabricFallRescueController.MAX_SECONDS
                                                )
                                            ).executes(context -> {
                                                FabricFallRescueController.Operation operation =
                                                    FabricFallRescueController.arm(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(
                                                            context,
                                                            "seconds"
                                                        )
                                                    );
                                                return emit(
                                                    context.getSource(),
                                                    operation.ok(),
                                                    operation.message()
                                                );
                                            })
                                        )
                                )
                                .then(
                                    Commands.literal("disarm").executes(context -> {
                                        FabricFallRescueController.Operation operation =
                                            FabricFallRescueController.disarm(
                                                context.getSource().getPlayerOrException()
                                            );
                                        return emit(
                                            context.getSource(),
                                            operation.ok(),
                                            operation.message()
                                        );
                                    })
                                )
                                .then(
                                    Commands.literal("status").executes(context -> {
                                        FabricFallRescueController.Operation operation =
                                            FabricFallRescueController.status(
                                                context.getSource().getPlayerOrException()
                                            );
                                        return emit(
                                            context.getSource(),
                                            operation.ok(),
                                            operation.message()
                                        );
                                    })
                                )
                        )
                        .then(
                            Commands.literal("combat_watchdog")
                                .then(
                                    Commands.literal("arm")
                                        .then(
                                            Commands.argument("player", EntityArgument.player())
                                                .then(
                                                    Commands.argument("mob_tag", StringArgumentType.word())
                                                        .then(
                                                            Commands.argument(
                                                                "health_floor",
                                                                DoubleArgumentType.doubleArg(1, 20)
                                                            ).then(
                                                                Commands.argument(
                                                                    "horizontal_radius",
                                                                    IntegerArgumentType.integer(
                                                                        FabricCombatArenaWatchdog.MIN_RADIUS,
                                                                        FabricCombatArenaWatchdog.MAX_RADIUS
                                                                    )
                                                                ).then(
                                                                    Commands.argument(
                                                                        "seconds",
                                                                        IntegerArgumentType.integer(
                                                                            FabricCombatArenaWatchdog.MIN_SECONDS,
                                                                            FabricCombatArenaWatchdog.MAX_SECONDS
                                                                        )
                                                                    ).executes(context -> {
                                                                        FabricCombatArenaWatchdog.Operation operation =
                                                                            FabricCombatArenaWatchdog.arm(
                                                                                EntityArgument.getPlayer(context, "player"),
                                                                                StringArgumentType.getString(context, "mob_tag"),
                                                                                DoubleArgumentType.getDouble(context, "health_floor"),
                                                                                IntegerArgumentType.getInteger(context, "horizontal_radius"),
                                                                                IntegerArgumentType.getInteger(context, "seconds")
                                                                            );
                                                                        return emit(
                                                                            context.getSource(),
                                                                            operation.ok(),
                                                                            operation.message()
                                                                        );
                                                                    })
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("disarm")
                                        .then(
                                            Commands.argument("player", EntityArgument.player())
                                                .executes(context -> {
                                                    FabricCombatArenaWatchdog.Operation operation =
                                                        FabricCombatArenaWatchdog.disarm(
                                                            context.getSource().getServer(),
                                                            EntityArgument.getPlayer(context, "player")
                                                        );
                                                    return emit(
                                                        context.getSource(),
                                                        operation.ok(),
                                                        operation.message()
                                                    );
                                                })
                                        )
                                )
                                .then(
                                    Commands.literal("status")
                                        .then(
                                            Commands.argument("player", EntityArgument.player())
                                                .executes(context -> {
                                                    FabricCombatArenaWatchdog.Operation operation =
                                                        FabricCombatArenaWatchdog.status(
                                                            EntityArgument.getPlayer(context, "player")
                                                        );
                                                    return emit(
                                                        context.getSource(),
                                                        operation.ok(),
                                                        operation.message()
                                                    );
                                                })
                                        )
                                )
                        )
                )
        );
    }

    static void tick(MinecraftServer server) {
        FabricFallRescueController.tick(server);
        FabricCombatArenaWatchdog.tick(server);
        for (ServerLevel level : server.getAllLevels()) {
            FabricRegionCheckpointStore.tick(level);
        }
    }

    static void clear(MinecraftServer server) {
        FabricFallRescueController.clear(server);
        FabricCombatArenaWatchdog.clear(server);
        FabricRegionCheckpointStore.clear();
    }

    private static int emit(
        CommandSourceStack source,
        boolean ok,
        String message
    ) {
        if (ok) {
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        }
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
