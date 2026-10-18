package com.casimirbot.helixplayer.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

final class PlayerActionClientCommands {
    private PlayerActionClientCommands() {}

    static void register(HelixFabricPlayerAgentClient agent) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                literal("helix-player")
                    .then(
                        literal("pair")
                            .then(
                                argument("code", StringArgumentType.word())
                                    .executes(context -> {
                                        agent.pairAsync(
                                            StringArgumentType.getString(context, "code"),
                                            null
                                        );
                                        return 1;
                                    })
                                    .then(
                                        argument("endpoint", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                agent.pairAsync(
                                                    StringArgumentType.getString(context, "code"),
                                                    StringArgumentType.getString(context, "endpoint")
                                                );
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(literal("status").executes(context -> {
                        agent.showStatus();
                        return 1;
                    }))
                    .then(
                        literal("diagnostic")
                            .then(literal("status").executes(context -> {
                                agent.showDiagnosticStatus();
                                return 1;
                            }))
                            .then(literal("cancel").executes(context -> {
                                agent.cancelDiagnostic();
                                return 1;
                            }))
                            .then(
                                literal("guardian")
                                    .then(
                                        literal("arm")
                                            .then(
                                                argument(
                                                    "duration_seconds",
                                                    IntegerArgumentType.integer(10, 1_800)
                                                ).executes(context -> {
                                                    agent.armDiagnosticGuardian(
                                                        IntegerArgumentType.getInteger(
                                                            context,
                                                            "duration_seconds"
                                                        )
                                                    );
                                                    return 1;
                                                })
                                            )
                                    )
                                    .then(literal("disarm").executes(context -> {
                                        agent.disarmDiagnosticGuardian();
                                        return 1;
                                    }))
                                    .then(literal("status").executes(context -> {
                                        agent.showDiagnosticGuardianStatus();
                                        return 1;
                                    }))
                            )
                            .then(
                                literal("inbox-enable")
                                    .then(literal("movement").executes(context -> {
                                        agent.enableDiagnosticInbox(
                                            PlayerActionDiagnosticInbox.Scope.MOVEMENT
                                        );
                                        return 1;
                                    }))
                                    .then(literal("full").executes(context -> {
                                        agent.enableDiagnosticInbox(
                                            PlayerActionDiagnosticInbox.Scope.FULL
                                        );
                                        return 1;
                                    }))
                            )
                            .then(literal("inbox-disable").executes(context -> {
                                agent.disableDiagnosticInbox();
                                return 1;
                            }))
                            .then(
                                literal("walk")
                                    .then(
                                        argument("direction", StringArgumentType.word())
                                            .then(
                                                argument(
                                                    "duration_ms",
                                                    IntegerArgumentType.integer(50, 10_000)
                                                )
                                                    .executes(context -> {
                                                        agent.startDiagnosticWalk(
                                                            StringArgumentType.getString(context, "direction"),
                                                            IntegerArgumentType.getInteger(context, "duration_ms"),
                                                            false
                                                        );
                                                        return 1;
                                                    })
                                                    .then(literal("sprint").executes(context -> {
                                                        agent.startDiagnosticWalk(
                                                            StringArgumentType.getString(context, "direction"),
                                                            IntegerArgumentType.getInteger(context, "duration_ms"),
                                                            true
                                                        );
                                                        return 1;
                                                    }))
                                            )
                                    )
                            )
                            .then(
                                literal("jump")
                                    .then(
                                        argument("count", IntegerArgumentType.integer(1, 10))
                                            .executes(context -> {
                                                agent.startDiagnosticJump(
                                                    IntegerArgumentType.getInteger(context, "count")
                                                );
                                                return 1;
                                            })
                                    )
                            )
                            .then(
                                literal("look-relative")
                                    .then(
                                        argument(
                                            "yaw_delta_degrees",
                                            DoubleArgumentType.doubleArg(-180.0, 180.0)
                                        )
                                            .then(
                                                argument(
                                                    "pitch_delta_degrees",
                                                    DoubleArgumentType.doubleArg(-180.0, 180.0)
                                                )
                                                    .executes(context -> {
                                                        agent.startDiagnosticRelativeLook(
                                                            DoubleArgumentType.getDouble(
                                                                context,
                                                                "yaw_delta_degrees"
                                                            ),
                                                            DoubleArgumentType.getDouble(
                                                                context,
                                                                "pitch_delta_degrees"
                                                            )
                                                        );
                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )
                    .then(literal("emergency-stop").executes(context -> {
                        agent.emergencyStop();
                        return 1;
                    }))
                    .then(literal("disconnect").executes(context -> {
                        agent.disconnectLocal();
                        return 1;
                    }))
            )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                literal("helix")
                    .then(
                        literal("ask")
                            .then(
                                argument("prompt", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        agent.ask(StringArgumentType.getString(context, "prompt"));
                                        return 1;
                                    })
                            )
                    )
                    .then(literal("status").executes(context -> {
                        agent.showStatus();
                        return 1;
                    }))
                    .then(literal("cancel").executes(context -> {
                        agent.cancelAsk();
                        return 1;
                    }))
                    .then(literal("emergency-stop").executes(context -> {
                        agent.emergencyStop();
                        return 1;
                    }))
            )
        );
    }
}
