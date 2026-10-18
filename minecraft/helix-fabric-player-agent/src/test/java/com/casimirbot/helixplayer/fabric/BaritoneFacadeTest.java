package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

public final class BaritoneFacadeTest {
    @Test
    void appliesMovementOnlySettingsBeforeStartingAndRestoresAfterSafeCancel()
        throws Exception {
        FakeSettings settings = new FakeSettings();
        FakeGoalProcess goals = new FakeGoalProcess();
        FakePathing pathing = new FakePathing();
        BaritoneFacade facade = facade(settings, goals, pathing);

        assertTrue(facade.start(4, 64, -2, 2));
        assertFalse(settings.allowBreak.value);
        assertTrue(settings.allowBreakAnyway.value.isEmpty());
        assertFalse(settings.allowPlace.value);
        assertFalse(settings.allowInventory.value);
        assertFalse(settings.allowSprint.value);
        assertEquals(new BlockPos(4, 64, -2), goals.goal.position);
        assertEquals(2, goals.goal.radius);

        pathing.pathing = true;
        assertEquals(BaritoneFacade.PathState.PATHING, facade.status().pathState());
        assertTrue(facade.cancel());
        assertTrue(settings.allowBreak.value);
        assertEquals(List.of("original-exception"), settings.allowBreakAnyway.value);
        assertTrue(settings.allowPlace.value);
        assertTrue(settings.allowInventory.value);
        assertTrue(settings.allowSprint.value);
    }

    @Test
    void failsClosedWhenTheMutationPolicyChangesDuringAnOwnedPath() throws Exception {
        FakeSettings settings = new FakeSettings();
        FakeGoalProcess goals = new FakeGoalProcess();
        FakePathing pathing = new FakePathing();
        BaritoneFacade facade = facade(settings, goals, pathing);
        assertTrue(facade.start(0, 64, 0, 1));
        pathing.pathing = true;

        settings.allowBreak.value = true;
        BaritoneFacade.Status status = facade.status();

        assertEquals(BaritoneFacade.PathState.POLICY_VIOLATION, status.pathState());
        assertEquals(1, pathing.cancelCalls);
        assertFalse(status.mutationPolicyIntact());
    }

    @Test
    void retainsRestrictionsUntilAnUnsafeCancellationActuallySettles() throws Exception {
        FakeSettings settings = new FakeSettings();
        FakeGoalProcess goals = new FakeGoalProcess();
        FakePathing pathing = new FakePathing();
        BaritoneFacade facade = facade(settings, goals, pathing);
        assertTrue(facade.start(0, 64, 0, 1));
        pathing.pathing = true;
        pathing.hasPath = true;
        pathing.cancelResult = false;

        assertFalse(facade.cancel());
        assertFalse(settings.allowBreak.value);
        assertFalse(settings.allowPlace.value);

        pathing.pathing = false;
        pathing.hasPath = false;
        goals.active = false;
        BaritoneFacade.Status settled = facade.status();

        assertEquals(BaritoneFacade.PathState.IDLE, settled.pathState());
        assertTrue(settings.allowBreak.value);
        assertTrue(settings.allowPlace.value);
    }

    @Test
    void reportsIdleWhenBaritoneSettlesBeforeTheExactHelixPostcondition()
        throws Exception {
        FakeSettings settings = new FakeSettings();
        FakeGoalProcess goals = new FakeGoalProcess();
        FakePathing pathing = new FakePathing();
        BaritoneFacade facade = facade(settings, goals, pathing);
        assertTrue(facade.start(0, 64, 0, 1));

        goals.active = false;
        BaritoneFacade.Status status = facade.status();

        assertEquals(BaritoneFacade.PathState.IDLE, status.pathState());
        assertTrue(status.goalOwned());
        assertFalse(status.processActive());
        assertTrue(status.mutationPolicyIntact());
    }

    @Test
    void terminalReleaseAfterEarlyIdleRestoresSettingsAndAllowsNextGoal()
        throws Exception {
        FakeSettings settings = new FakeSettings();
        FakeGoalProcess goals = new FakeGoalProcess();
        FakePathing pathing = new FakePathing();
        BaritoneFacade facade = facade(settings, goals, pathing);
        assertTrue(facade.start(0, 64, 0, 1));

        // Baritone has settled, but the owner has not reached its measured
        // destination. The workflow's failed terminal release must still
        // cancel the owned goal rather than only releasing held keys.
        goals.active = false;
        assertEquals(BaritoneFacade.PathState.IDLE, facade.status().pathState());
        assertTrue(facade.cancel());
        assertEquals(1, pathing.cancelCalls);
        assertTrue(settings.allowBreak.value);
        assertTrue(settings.allowPlace.value);
        assertTrue(facade.start(2, 64, 0, 1));
        assertEquals(new BlockPos(2, 64, 0), goals.goal.position);
        assertTrue(facade.cancel());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BaritoneFacade facade(
        FakeSettings settings,
        FakeGoalProcess goals,
        FakePathing pathing
    ) throws Exception {
        Method setting = BaritoneFacade.class.getDeclaredMethod(
            "setting", Object.class, String.class, Object.class
        );
        setting.setAccessible(true);
        List<Object> bindings = new ArrayList<>();
        bindings.add(setting.invoke(null, settings, "allowBreak", false));
        bindings.add(setting.invoke(null, settings, "allowBreakAnyway", List.of()));
        bindings.add(setting.invoke(null, settings, "allowPlace", false));
        bindings.add(setting.invoke(null, settings, "allowPlaceInFluidsSource", false));
        bindings.add(setting.invoke(null, settings, "allowPlaceInFluidsFlow", false));
        bindings.add(setting.invoke(null, settings, "allowInventory", false));
        bindings.add(setting.invoke(null, settings, "allowSprint", false));
        bindings.add(setting.invoke(null, settings, "allowParkour", false));
        bindings.add(setting.invoke(null, settings, "allowParkourPlace", false));
        Constructor<BaritoneFacade> constructor = BaritoneFacade.class.getDeclaredConstructor(
            Object.class, Object.class, Object.class, Constructor.class,
            Method.class, Method.class, Method.class, Method.class, Method.class,
            Method.class, Method.class, List.class, String.class, String.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            new Object(),
            goals,
            pathing,
            FakeGoalNear.class.getConstructor(BlockPos.class, int.class),
            FakeGoalProcess.class.getMethod("setGoalAndPath", FakeGoal.class),
            FakeGoalProcess.class.getMethod("isActive"),
            FakePathing.class.getMethod("isPathing"),
            FakePathing.class.getMethod("hasPath"),
            FakePathing.class.getMethod("getInProgress"),
            FakePathing.class.getMethod("estimatedTicksToGoal"),
            FakePathing.class.getMethod("cancelEverything"),
            bindings,
            "test-1.15.0",
            ""
        );
    }

    public interface FakeGoal {}

    public static final class FakeGoalNear implements FakeGoal {
        final BlockPos position;
        final int radius;

        public FakeGoalNear(BlockPos position, int radius) {
            this.position = position;
            this.radius = radius;
        }
    }

    public static final class FakeSetting<T> {
        public T value;

        FakeSetting(T value) {
            this.value = value;
        }
    }

    public static final class FakeSettings {
        public final FakeSetting<Boolean> allowBreak = new FakeSetting<>(true);
        public final FakeSetting<List<String>> allowBreakAnyway =
            new FakeSetting<>(List.of("original-exception"));
        public final FakeSetting<Boolean> allowPlace = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowPlaceInFluidsSource = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowPlaceInFluidsFlow = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowInventory = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowSprint = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowParkour = new FakeSetting<>(true);
        public final FakeSetting<Boolean> allowParkourPlace = new FakeSetting<>(true);
    }

    public static final class FakeGoalProcess {
        FakeGoalNear goal;
        boolean active;

        public void setGoalAndPath(FakeGoal goal) {
            this.goal = (FakeGoalNear) goal;
            active = true;
        }

        public boolean isActive() {
            return active;
        }
    }

    public static final class FakePathing {
        boolean pathing;
        boolean hasPath;
        boolean calculating;
        boolean cancelResult = true;
        int cancelCalls;

        public boolean isPathing() { return pathing; }
        public boolean hasPath() { return hasPath; }
        public Optional<String> getInProgress() {
            return calculating ? Optional.of("calculation") : Optional.empty();
        }
        public Optional<Double> estimatedTicksToGoal() { return Optional.of(12.0); }
        public boolean cancelEverything() {
            cancelCalls++;
            if (cancelResult) {
                pathing = false;
                hasPath = false;
            }
            return cancelResult;
        }
    }
}
