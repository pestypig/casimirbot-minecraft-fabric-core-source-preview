package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FabricCommandClassifierTest {
    @Test
    void classifiesRepresentativeVanillaCommandsConservatively() {
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("locate structure minecraft:end_city")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "world_time_weather",
                "world_mutation"
            ),
            FabricCommandClassifier.classify("time set day")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "world_build",
                "world_mutation"
            ),
            FabricCommandClassifier.classify("setblock 0 64 0 minecraft:stone")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "server_administration",
                "server_administration"
            ),
            FabricCommandClassifier.classify("stop")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "server_administration",
                "server_administration"
            ),
            FabricCommandClassifier.classify("say Helix admin test")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "server_administration",
                "server_administration"
            ),
            FabricCommandClassifier.classify(
                "execute at @s run playsound minecraft:block.amethyst_block.chime master @s ~ ~ ~ 1 1 1"
            )
        );
    }

    @Test
    void classifiesExecuteByItsNestedCommandAndUnknownModsAsUnknown() {
        assertEquals(
            new FabricCommandClassifier.Classification(
                "player_inventory",
                "player_mutation"
            ),
            FabricCommandClassifier.classify(
                "execute as @s at @s run give @s minecraft:torch 4"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("mod_command", "unknown"),
            FabricCommandClassifier.classify("crimsoncurse inspect @s")
        );
    }

    @Test
    void classifiesReadFormsOfOtherwiseMutatingRootsAsQueries() {
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("time query daytime")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("gamerule keepInventory")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("difficulty")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("worldborder get")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("tick query")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "world_time_weather",
                "world_mutation"
            ),
            FabricCommandClassifier.classify("time set day")
        );
    }

    @Test
    void classifiesMutatingDataCommandsByTheirTargetKind() {
        assertEquals(
            new FabricCommandClassifier.Classification("entity_control", "world_mutation"),
            FabricCommandClassifier.classify("data merge entity @s {NoAI:0b}")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("entity_control", "world_mutation"),
            FabricCommandClassifier.classify(
                "execute as @a run data modify entity @s Health set value 20f"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("entity_control", "world_mutation"),
            FabricCommandClassifier.classify("data remove entity @s NoGravity")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("world_build", "world_mutation"),
            FabricCommandClassifier.classify("data merge block 0 80 0 {Lock:\"Helix\"}")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("world_build", "world_mutation"),
            FabricCommandClassifier.classify(
                "data modify storage helix:test value set value 1"
            )
        );
    }

    @Test
    void classifiesEveryControlledCourseCommandRootAcrossNestedExecuteForms() {
        assertEquals(
            new FabricCommandClassifier.Classification("world_build", "world_mutation"),
            FabricCommandClassifier.classify(
                "execute in minecraft:overworld run fill 92 64 -208 108 71 -192 minecraft:air replace"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("world_build", "world_mutation"),
            FabricCommandClassifier.classify(
                "execute in minecraft:overworld run setblock 97 65 -200 minecraft:furnace replace"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("player_state", "player_mutation"),
            FabricCommandClassifier.classify("gamemode survival FixturePlayer")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("player_state", "player_mutation"),
            FabricCommandClassifier.classify("effect clear FixturePlayer")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("player_inventory", "player_mutation"),
            FabricCommandClassifier.classify("clear FixturePlayer")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("player_inventory", "player_mutation"),
            FabricCommandClassifier.classify(
                "give FixturePlayer minecraft:flint_and_steel 1"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("entity_control", "world_mutation"),
            FabricCommandClassifier.classify(
                "execute in minecraft:overworld run summon minecraft:item 95 65 -198 {Item:{id:\"minecraft:cobblestone\",count:1}}"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("player_movement", "player_mutation"),
            FabricCommandClassifier.classify(
                "execute in minecraft:overworld run tp FixturePlayer 94 65 -200 -90 0"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify(
                "execute in minecraft:overworld run execute unless block 100 66 -200 minecraft:nether_portal run data get entity FixturePlayer Pos"
            )
        );
    }
}
