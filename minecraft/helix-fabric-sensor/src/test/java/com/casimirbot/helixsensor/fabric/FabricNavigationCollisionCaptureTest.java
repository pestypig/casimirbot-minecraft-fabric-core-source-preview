package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class FabricNavigationCollisionCaptureTest {
    static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    static class FixtureView implements FabricNavigationCollisionCapture.View {
        boolean thread = true, available = true, unloaded = false, outsideHeight = false;
        int reads = 0, identities = 0, loadedChecks = 0, changeAfter = 1;
        long tick = 100;
        BlockPos origin = BlockPos.ZERO;
        String change = "none";
        final HashSet<BlockPos> readPositions = new HashSet<>();
        public boolean onServerThread() { return thread; }
        public FabricNavigationCollisionCapture.Identity identity() {
            identities++;
            boolean altered = identities > changeAfter;
            return new FabricNavigationCollisionCapture.Identity(
                altered && change.equals("dimension") ? "minecraft:the_nether" : "minecraft:overworld",
                altered && change.equals("player") ? new UUID(0, 2) : PLAYER,
                altered && change.equals("position") ? origin.offset(1, 0, 0) : origin,
                altered && change.equals("tick") ? tick + 1 : tick,
                available && !(altered && change.equals("availability")));
        }
        public boolean inBuildHeight(BlockPos p) { return !outsideHeight; }
        public boolean loaded(BlockPos p) { loadedChecks++; return !unloaded; }
        public Map<String, Object> measure(BlockPos p) {
            reads++;
            assertTrue(readPositions.add(p));
            return FabricNavigationCollisionFacts.measure(true,
                (p.getY() < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(),
                EmptyBlockGetter.INSTANCE, p, CollisionContext.empty());
        }
    }
    static FabricNavigationCollisionCapture.Capture capture(FixtureView f) {
        return FabricNavigationCollisionCapture.capture(f, 2, 2, () -> 100L);
    }

    @Test void capturesOneBoundedVolumeWithOneReadPerObservedCell() {
        FixtureView f = new FixtureView();
        var r = capture(f);
        assertEquals(125, r.cellCount()); assertEquals(125, f.reads);
        assertEquals(0, r.unknownCount()); assertEquals(0, r.unsupportedCount());
        assertEquals(100L, r.replay().get("tick_start"));
        assertEquals(100L, r.replay().get("tick_end"));
        assertEquals(PLAYER.toString(), r.replay().get("subject_native_id"));
        assertTrue(r.payloadBytes() < FabricNavigationCollisionCapture.MAX_BYTES);
        assertEquals(0L, r.elapsedNanos());
    }
    @Test void wrongThreadRejectsBeforeIdentityOrCellReads() {
        FixtureView f = new FixtureView(); f.thread = false;
        assertTrue(assertThrows(IllegalStateException.class, () -> capture(f)).getMessage().contains("wrong_thread"));
        assertEquals(0, f.reads); assertEquals(0, f.identities);
    }
    @Test void unavailableSelectedPlayerRejectsBeforeCellReads() {
        FixtureView f = new FixtureView(); f.available = false;
        assertThrows(IllegalStateException.class, () -> capture(f)); assertEquals(0, f.reads);
    }
    @Test void unloadedCellsNeverReachNativeStateMeasurement() {
        FixtureView f = new FixtureView(); f.unloaded = true;
        var r = capture(f);
        assertEquals(125, r.unknownCount()); assertEquals(0, f.reads);
    }
    @Test void outsideBuildHeightNeverQueriesChunksOrNativeState() {
        FixtureView f = new FixtureView(); f.outsideHeight = true;
        var r = capture(f);
        assertEquals(125, r.unknownCount()); assertEquals(0, f.loadedChecks); assertEquals(0, f.reads);
    }
    @Test void changedTickPlayerDimensionPositionOrAvailabilityDiscardsWholeCapture() {
        for (String changed : new String[] {"tick", "player", "dimension", "position", "availability"}) {
            FixtureView f = new FixtureView(); f.change = changed;
            assertTrue(assertThrows(IllegalStateException.class, () -> capture(f)).getMessage().contains("capture_identity_or_tick_changed"));
        }
    }
    @Test void lostThreadDuringCaptureAbortsBeforeNextMeasurement() {
        FixtureView f = new FixtureView() {
            @Override public Map<String, Object> measure(BlockPos p) {
                var result = super.measure(p); thread = false; return result;
            }
        };
        assertThrows(IllegalStateException.class, () -> capture(f)); assertEquals(1, f.reads);
    }
    @Test void revalidatesIdentityAgainAtPublication() {
        FixtureView f = new FixtureView(); f.change = "tick"; f.changeAfter = 2;
        assertTrue(assertThrows(IllegalStateException.class, () -> capture(f)).getMessage().contains("capture_identity_or_tick_changed"));
        assertEquals(3, f.identities);
    }
    @Test void elapsedBudgetAndClockRegressionRejectRatherThanReturnPartialEvidence() {
        FixtureView f = new FixtureView(); AtomicLong clock = new AtomicLong();
        assertThrows(IllegalStateException.class, () -> FabricNavigationCollisionCapture.capture(f, 2, 2,
            () -> clock.getAndAdd(FabricNavigationCollisionCapture.MAX_NANOS + 1)));
        assertEquals(0, f.reads);
        AtomicLong backwards = new AtomicLong(10);
        assertThrows(IllegalStateException.class, () -> FabricNavigationCollisionCapture.capture(new FixtureView(), 2, 2, backwards::getAndDecrement));
    }
    @Test void radiusCoordinateAndTickBoundsRejectBeforeReadingWorld() {
        for (int radius : new int[] {-1, 0, 8, Integer.MAX_VALUE}) {
            FixtureView f = new FixtureView();
            assertThrows(IllegalStateException.class, () -> FabricNavigationCollisionCapture.capture(f, radius, 2, () -> 0));
            assertEquals(0, f.reads);
        }
        assertThrows(IllegalStateException.class, () -> FabricNavigationCollisionCapture.capture(new FixtureView(), 2, 9, () -> 0));
        FixtureView f = new FixtureView(); f.origin = new BlockPos(Integer.MIN_VALUE, 0, 0);
        assertThrows(IllegalStateException.class, () -> capture(f)); assertEquals(0, f.reads);
        FixtureView time = new FixtureView(); time.tick = Long.MAX_VALUE;
        assertThrows(IllegalStateException.class, () -> capture(time)); assertEquals(0, time.reads);
    }
    @Test void maximumRadiusStillRespects4096CellCeiling() {
        FixtureView f = new FixtureView(); f.unloaded = true;
        assertEquals(3825, FabricNavigationCollisionCapture.capture(f, 7, 8, () -> 0).cellCount());
    }
    @Test void oversizedPayloadIsNotPublished() {
        FixtureView f = new FixtureView() {
            @Override public Map<String, Object> measure(BlockPos p) { return Map.of("oversized", "x".repeat(20_000)); }
        };
        assertTrue(assertThrows(IllegalStateException.class, () -> capture(f)).getMessage().contains("payload_budget_exceeded"));
    }
    @Test void exportsCaptureCollectorReplayWithNativeMeasuredFacts() throws Exception {
        var r = capture(new FixtureView());
        Path output = Path.of("build/nav1c/selected-player-capture-fixture.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(r.replay()));
    }
}
