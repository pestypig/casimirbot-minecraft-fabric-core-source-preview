package com.casimirbot.helixplayer.fabric.gametest;

import com.casimirbot.helixsensor.combat.ProjectileThreatForecaster;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public final class CombatArenaGameTests {
    private static final String STRUCTURE =
        "helix_fabric_player_agent:c0_zombie_baseline_ring";
    private static final String C1_STRUCTURE =
        "helix_fabric_player_agent:c1_projectile_calibration";
    private static final ProjectileThreatForecaster.Box C1_ACTOR_BOX =
        new ProjectileThreatForecaster.Box(8.2, 1, 8.2, 8.8, 2.8, 8.8);

    @GameTest(structure = STRUCTURE, maxTicks = 40, skyAccess = false)
    public void c0ZombieFixtureHasOneEligibleOpponent(GameTestHelper helper) {
        buildRing(helper);
        Zombie zombie = spawnBaselineZombie(helper);

        helper.assertEntitiesPresent(EntityType.ZOMBIE, 1);
        helper.assertTrue(zombie instanceof Monster, Component.literal(
            "C0 primary entity must use vanilla hostile classification."
        ));
        helper.assertTrue(zombie.isAlive(), Component.literal(
            "C0 primary zombie must begin alive."
        ));
        helper.assertFalse(zombie.isBaby(), Component.literal(
            "C0 primary zombie must be an adult."
        ));
        helper.assertTrue(
            helper.getLevel().getEntitiesOfClass(Monster.class, helper.getBounds()).size() == 1,
            Component.literal("C0 must contain exactly one hostile entity.")
        );
        helper.assertTrue(
            helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds()).isEmpty(),
            Component.literal("C0 must contain no projectile source or projectile.")
        );
        for (EquipmentSlot slot : new EquipmentSlot[] {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND
        }) {
            helper.assertTrue(zombie.getItemBySlot(slot).isEmpty(), Component.literal(
                "C0 primary zombie must be unarmored and unarmed."
            ));
        }
        helper.succeed();
    }

    @GameTest(structure = STRUCTURE, maxTicks = 40, skyAccess = false)
    public void c0ZombieExposesVanillaHurtAndDeathLifecycle(GameTestHelper helper) {
        buildRing(helper);
        Zombie zombie = spawnBaselineZombie(helper);
        float initialHealth = zombie.getHealth();

        helper.hurt(zombie, helper.getLevel().damageSources().generic(), 3.0F);
        helper.assertTrue(zombie.getHealth() < initialHealth, Component.literal(
            "C0 must expose an authoritative vanilla health transition."
        ));
        helper.kill(zombie);
        helper.assertFalse(zombie.isAlive(), Component.literal(
            "C0 must expose the vanilla death lifecycle."
        ));
        helper.succeed();
    }

    @GameTest(structure = C1_STRUCTURE, maxTicks = 40, skyAccess = false)
    public void c1ProjectileFixtureRecallsEverySupportedCollisionLane(GameTestHelper helper) {
        buildC1CalibrationRing(helper);
        Arrow front = spawnArrow(helper, new BlockPos(2, 2, 8), 1, 0, 0);
        Arrow rear = spawnArrow(helper, new BlockPos(14, 2, 8), -1, 0, 0);
        Arrow left = spawnArrow(helper, new BlockPos(8, 2, 2), 0, 0, 1);
        Arrow right = spawnArrow(helper, new BlockPos(8, 2, 14), 0, 0, -1);

        helper.assertEntitiesPresent(EntityType.ARROW, 4);
        ProjectileThreatForecaster.Forecast[] forecasts = {
            forecastArrow(2.5, 2, 8.5, front.getDeltaMovement().x, 0, 0, true, null),
            forecastArrow(14.5, 2, 8.5, rear.getDeltaMovement().x, 0, 0, true, null),
            forecastArrow(8.5, 2, 2.5, 0, 0, left.getDeltaMovement().z, true, null),
            forecastArrow(8.5, 2, 14.5, 0, 0, right.getDeltaMovement().z, true, null),
        };
        for (ProjectileThreatForecaster.Forecast forecast : forecasts) {
            helper.assertTrue(
                forecast.classification() ==
                    ProjectileThreatForecaster.ThreatClassification.COLLISION,
                Component.literal("Every supported C1 collision lane must classify collision.")
            );
            helper.assertTrue(forecast.predictedCollisionTick() != null, Component.literal(
                "Every supported C1 collision lane must expose an impact tick."
            ));
        }
        helper.succeed();
    }

    @GameTest(structure = C1_STRUCTURE, maxTicks = 40, skyAccess = false)
    public void c1ProjectileFixtureSeparatesNearMissOcclusionAndUnknown(GameTestHelper helper) {
        buildC1CalibrationRing(helper);
        helper.setBlock(5, 2, 8, Blocks.STONE);

        ProjectileThreatForecaster.Forecast nearMiss = forecastArrow(
            2.5, 2, 9.3, 1, 0, 0, true, null
        );
        ProjectileThreatForecaster.Forecast occluded = forecastArrow(
            2.5, 2, 8.5, 1, 0, 0, true, 3
        );
        ProjectileThreatForecaster.Forecast incomplete = forecastArrow(
            2.5, 2, 12.5, 1, 0, 0, false, null
        );

        helper.assertTrue(
            nearMiss.classification() ==
                ProjectileThreatForecaster.ThreatClassification.NEAR_MISS,
            Component.literal("The labelled C1 lateral lane must remain a near miss.")
        );
        helper.assertTrue(
            occluded.classification() ==
                ProjectileThreatForecaster.ThreatClassification.SAFE && occluded.occluded(),
            Component.literal("Verified pre-impact occlusion must block the collision lane.")
        );
        helper.assertTrue(
            incomplete.classification() ==
                ProjectileThreatForecaster.ThreatClassification.UNKNOWN,
            Component.literal("Incomplete projectile support must never classify safe.")
        );
        helper.succeed();
    }

    private static Zombie spawnBaselineZombie(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(13, 1, 8));
        zombie.setBaby(false);
        zombie.setCanPickUpLoot(false);
        zombie.setPersistenceRequired();
        for (EquipmentSlot slot : new EquipmentSlot[] {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND
        }) {
            zombie.setItemSlot(slot, ItemStack.EMPTY);
        }
        return zombie;
    }

    private static Arrow spawnArrow(
        GameTestHelper helper,
        BlockPos position,
        double velocityX,
        double velocityY,
        double velocityZ
    ) {
        Arrow arrow = helper.spawn(EntityType.ARROW, position);
        arrow.setDeltaMovement(velocityX, velocityY, velocityZ);
        return arrow;
    }

    private static ProjectileThreatForecaster.Forecast forecastArrow(
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean evidenceComplete,
        Integer verifiedOcclusionTick
    ) {
        return ProjectileThreatForecaster.forecast(new ProjectileThreatForecaster.Input(
            new ProjectileThreatForecaster.Vector(x, y, z),
            new ProjectileThreatForecaster.Vector(velocityX, velocityY, velocityZ),
            new ProjectileThreatForecaster.Vector(0, -0.05, 0),
            0.99,
            20,
            C1_ACTOR_BOX,
            0.75,
            evidenceComplete,
            verifiedOcclusionTick
        ));
    }

    private static void buildRing(GameTestHelper helper) {
        for (int x = 0; x <= 16; x++) {
            for (int z = 0; z <= 16; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
                helper.setBlock(x, 6, z, Blocks.STONE);
            }
        }
        for (int y = 1; y < 6; y++) {
            for (int index = 0; index <= 16; index++) {
                helper.setBlock(0, y, index, Blocks.STONE);
                helper.setBlock(16, y, index, Blocks.STONE);
                helper.setBlock(index, y, 0, Blocks.STONE);
                helper.setBlock(index, y, 16, Blocks.STONE);
            }
        }
        helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, 0, 8));
        helper.assertBlockPresent(Blocks.STONE, new BlockPos(0, 3, 8));
        helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, 6, 8));
    }

    private static void buildC1CalibrationRing(GameTestHelper helper) {
        buildRing(helper);
        for (int index = 2; index <= 14; index++) {
            helper.setBlock(index, 1, 8, Blocks.SMOOTH_STONE);
            helper.setBlock(8, 1, index, Blocks.SMOOTH_STONE);
        }
        helper.assertBlockPresent(Blocks.SMOOTH_STONE, new BlockPos(8, 1, 8));
    }
}
