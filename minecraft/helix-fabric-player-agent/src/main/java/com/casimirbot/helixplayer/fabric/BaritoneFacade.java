package com.casimirbot.helixplayer.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/** Optional, fail-closed bridge to Baritone's public API only. */
final class BaritoneFacade {
    enum PathState {
        UNAVAILABLE, IDLE, CALCULATING, PATHING, PAUSED_OR_PENDING,
        POLICY_VIOLATION, ERROR
    }

    record Status(
        boolean available,
        String version,
        PathState pathState,
        boolean goalOwned,
        boolean processActive,
        boolean mutationPolicyIntact,
        boolean safeCancelLastResult,
        Double estimatedTicksToGoal,
        String lastError
    ) {}

    private record SettingBinding(
        String name,
        Object setting,
        Field valueField,
        Object restrictedValue
    ) {}

    private final Object baritone;
    private final Object goalProcess;
    private final Object pathingBehavior;
    private final Constructor<?> goalNearConstructor;
    private final Method setGoalAndPath;
    private final Method goalProcessIsActive;
    private final Method isPathing;
    private final Method hasPath;
    private final Method getInProgress;
    private final Method estimatedTicksToGoal;
    private final Method cancelEverything;
    private final List<SettingBinding> restrictedSettings;
    private final String version;
    private final Map<String, Object> originalSettingValues = new LinkedHashMap<>();
    private boolean goalOwned;
    private boolean safeCancelLastResult = true;
    private String lastError;

    private BaritoneFacade(
        Object baritone,
        Object goalProcess,
        Object pathingBehavior,
        Constructor<?> goalNearConstructor,
        Method setGoalAndPath,
        Method goalProcessIsActive,
        Method isPathing,
        Method hasPath,
        Method getInProgress,
        Method estimatedTicksToGoal,
        Method cancelEverything,
        List<SettingBinding> restrictedSettings,
        String version,
        String lastError
    ) {
        this.baritone = baritone;
        this.goalProcess = goalProcess;
        this.pathingBehavior = pathingBehavior;
        this.goalNearConstructor = goalNearConstructor;
        this.setGoalAndPath = setGoalAndPath;
        this.goalProcessIsActive = goalProcessIsActive;
        this.isPathing = isPathing;
        this.hasPath = hasPath;
        this.getInProgress = getInProgress;
        this.estimatedTicksToGoal = estimatedTicksToGoal;
        this.cancelEverything = cancelEverything;
        this.restrictedSettings = List.copyOf(restrictedSettings);
        this.version = version;
        this.lastError = lastError;
    }

    static BaritoneFacade discover() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object goalProcess = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object settings = apiClass.getMethod("getSettings").invoke(null);
            List<SettingBinding> restrictedSettings = List.of(
                setting(settings, "allowBreak", false),
                setting(settings, "allowBreakAnyway", List.of()),
                setting(settings, "allowPlace", false),
                setting(settings, "allowPlaceInFluidsSource", false),
                setting(settings, "allowPlaceInFluidsFlow", false),
                setting(settings, "allowInventory", false),
                setting(settings, "allowSprint", false),
                setting(settings, "allowParkour", false),
                setting(settings, "allowParkourPlace", false)
            );
            return new BaritoneFacade(
                baritone,
                goalProcess,
                pathingBehavior,
                goalNearClass.getConstructor(BlockPos.class, int.class),
                goalProcess.getClass().getMethod("setGoalAndPath", goalClass),
                goalProcess.getClass().getMethod("isActive"),
                pathingBehavior.getClass().getMethod("isPathing"),
                pathingBehavior.getClass().getMethod("hasPath"),
                pathingBehavior.getClass().getMethod("getInProgress"),
                pathingBehavior.getClass().getMethod("estimatedTicksToGoal"),
                pathingBehavior.getClass().getMethod("cancelEverything"),
                restrictedSettings,
                discoverVersion(apiClass),
                ""
            );
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return unavailable("baritone_public_api_incompatible");
        }
    }

    private static BaritoneFacade unavailable(String error) {
        return new BaritoneFacade(
            null, null, null, null, null, null, null, null, null, null, null,
            List.of(), "unavailable", error
        );
    }

    private static SettingBinding setting(
        Object settings,
        String name,
        Object restrictedValue
    ) throws ReflectiveOperationException {
        Object setting = settings.getClass().getField(name).get(settings);
        Field valueField = setting.getClass().getField("value");
        return new SettingBinding(name, setting, valueField, restrictedValue);
    }

    private static String discoverVersion(Class<?> apiClass) {
        String version = apiClass.getPackage() == null
            ? null
            : apiClass.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) return version;
        try {
            return FabricLoader.getInstance()
                .getModContainer("baritone")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .filter(value -> !value.isBlank())
                .orElse("installed");
        } catch (RuntimeException | LinkageError ignored) {
            // The API compatibility probe still decides availability below.
        }
        return "installed";
    }

    boolean available() {
        return baritone != null;
    }

    String version() {
        return version;
    }

    synchronized boolean start(int x, int y, int z, int radius) {
        if (!available()) return false;
        if (radius < 1 || radius > 64) {
            lastError = "baritone_goal_radius_invalid";
            return false;
        }
        try {
            if (goalOwned || activeOutsideHelix()) {
                lastError = "baritone_engine_already_active";
                return false;
            }
            applyRestrictedSettings();
            if (!mutationPolicyIntact()) {
                restoreRestrictedSettings();
                lastError = "baritone_mutation_policy_not_applied";
                return false;
            }
            Object goal = goalNearConstructor.newInstance(new BlockPos(x, y, z), radius);
            setGoalAndPath.invoke(goalProcess, goal);
            goalOwned = true;
            safeCancelLastResult = true;
            lastError = "";
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            goalOwned = false;
            restoreRestrictedSettings();
            lastError = "baritone_goal_start_failed";
            return false;
        }
    }

    synchronized boolean isPathing() {
        if (!available()) return false;
        if (restrictionLeaseActive() && !mutationPolicyIntact()) {
            lastError = "baritone_mutation_policy_changed_during_path";
            cancel();
            return false;
        }
        return invokeBoolean(isPathing, pathingBehavior, false);
    }

    synchronized Status status() {
        if (!available()) {
            return new Status(
                false, version, PathState.UNAVAILABLE, false, false, false, true,
                null, lastError
            );
        }
        boolean policyIntact = !restrictionLeaseActive() || mutationPolicyIntact();
        if (!policyIntact) {
            lastError = "baritone_mutation_policy_changed_during_path";
            cancel();
            return new Status(
                true, version, PathState.POLICY_VIOLATION, false, false, false,
                safeCancelLastResult, null, lastError
            );
        }
        try {
            boolean pathing = invokeBoolean(isPathing, pathingBehavior, false);
            boolean path = invokeBoolean(hasPath, pathingBehavior, false);
            boolean calculating = optionalPresent(getInProgress.invoke(pathingBehavior));
            boolean processActive = invokeBoolean(goalProcessIsActive, goalProcess, false);
            Double estimate = optionalDouble(estimatedTicksToGoal.invoke(pathingBehavior));
            if (restrictionLeaseActive() && !pathing && !path &&
                !calculating && !processActive) {
                restoreRestrictedSettings();
            }
            PathState state = pathing
                ? PathState.PATHING
                : calculating
                    ? PathState.CALCULATING
                    : (path || processActive)
                        ? PathState.PAUSED_OR_PENDING
                        : PathState.IDLE;
            return new Status(
                true, version, state, goalOwned, processActive, policyIntact,
                safeCancelLastResult, estimate, lastError
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            lastError = "baritone_status_failed";
            return new Status(
                true, version, PathState.ERROR, goalOwned, false, policyIntact,
                safeCancelLastResult, null, lastError
            );
        }
    }

    synchronized boolean cancel() {
        if (!available()) return true;
        try {
            safeCancelLastResult = invokeBoolean(cancelEverything, pathingBehavior, false);
        } finally {
            goalOwned = false;
            if (safeCancelLastResult) {
                restoreRestrictedSettings();
            } else {
                lastError = "baritone_cancel_pending_safe_segment";
            }
        }
        return safeCancelLastResult;
    }

    private boolean activeOutsideHelix() throws ReflectiveOperationException {
        return invokeBoolean(goalProcessIsActive, goalProcess, false) ||
            invokeBoolean(isPathing, pathingBehavior, false) ||
            invokeBoolean(hasPath, pathingBehavior, false) ||
            optionalPresent(getInProgress.invoke(pathingBehavior));
    }

    private void applyRestrictedSettings() throws ReflectiveOperationException {
        originalSettingValues.clear();
        for (SettingBinding binding : restrictedSettings) {
            originalSettingValues.put(
                binding.name(),
                binding.valueField().get(binding.setting())
            );
            binding.valueField().set(binding.setting(), binding.restrictedValue());
        }
    }

    private boolean mutationPolicyIntact() {
        if (!available() || originalSettingValues.isEmpty()) return false;
        try {
            for (SettingBinding binding : restrictedSettings) {
                Object current = binding.valueField().get(binding.setting());
                if (!binding.restrictedValue().equals(current)) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private boolean restrictionLeaseActive() {
        return !originalSettingValues.isEmpty();
    }

    private void restoreRestrictedSettings() {
        if (originalSettingValues.isEmpty()) return;
        for (SettingBinding binding : restrictedSettings) {
            if (!originalSettingValues.containsKey(binding.name())) continue;
            try {
                Object current = binding.valueField().get(binding.setting());
                if (binding.restrictedValue().equals(current)) {
                    binding.valueField().set(
                        binding.setting(),
                        originalSettingValues.get(binding.name())
                    );
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                lastError = "baritone_setting_restore_failed";
            }
        }
        originalSettingValues.clear();
    }

    private static boolean invokeBoolean(Method method, Object target, boolean fallback) {
        try {
            return Boolean.TRUE.equals(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return fallback;
        }
    }

    private static boolean optionalPresent(Object value) {
        return value instanceof Optional<?> optional && optional.isPresent();
    }

    private static Double optionalDouble(Object value) {
        if (!(value instanceof Optional<?> optional) || optional.isEmpty()) return null;
        Object item = optional.get();
        return item instanceof Number number && Double.isFinite(number.doubleValue())
            ? number.doubleValue()
            : null;
    }
}
