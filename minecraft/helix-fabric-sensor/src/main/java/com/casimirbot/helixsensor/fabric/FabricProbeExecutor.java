package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.HelixSensorRuntimeStatus;
import com.casimirbot.helixsensor.probe.ProbeContractGuard;
import com.casimirbot.helixsensor.scope.SensorScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FabricProbeExecutor {
    record ActorCandidate(
        int index,
        String canonicalId,
        String uuid,
        String actorLabel
    ) {}
    record ActorSelection(String status, Integer selectedIndex) {}
    private record PlayerResolution(
        ServerPlayer player,
        String failureCode,
        String failureSummary
    ) {}
    private record RecipeResultItems(List<String> itemIds, boolean complete) {}

    private final MinecraftServer server;
    private final HelixSensorConfig config;
    private final ProbeContractGuard guard;
    private final HelixSensorRuntimeStatus runtimeStatus;

    public FabricProbeExecutor(
        MinecraftServer server,
        HelixSensorConfig config,
        HelixSensorRuntimeStatus runtimeStatus
    ) {
        this.server = server;
        this.config = config;
        this.guard = new ProbeContractGuard();
        this.runtimeStatus = runtimeStatus;
    }

    public Map<String, Object> executeOnServerThread(
        Map<String, Object> probe
    ) {
        if (guard.isForbiddenAction(probe)) {
            runtimeStatus.recordForbiddenProbeBlocked();
            return blocked(
                probe,
                "Probe type would require live action and is forbidden."
            );
        }
        if (!guard.isKnownReadOnlyProbe(probe)) {
            return blocked(probe, "Probe type is unknown and blocked by policy.");
        }
        if (!guard.isReadOnly(probe)) {
            return blocked(probe, "Probe is not read-only.");
        }
        String probeType = String.valueOf(probe.get("probe_type"));
        if ("registry_fact".equals(probeType)) {
            return registryFact(probe);
        }
        if ("recipe_fact".equals(probeType)) {
            return recipeFact(probe);
        }
        PlayerResolution resolution = resolvePlayer(probe);
        if (resolution.player() == null) {
            return failed(
                probe,
                resolution.failureSummary(),
                resolution.failureCode()
            );
        }
        ServerPlayer player = resolution.player();
        return switch (probeType) {
            case "actor_status" -> actorStatus(probe, player);
            case "inventory_check" -> inventoryCheck(probe, player);
            case "nearby_entities" -> nearbyEntities(probe, player);
            case "line_of_sight" -> lineOfSight(probe, player);
            case "reachability" -> reachability(probe, player);
            case "crop_state" -> cropState(probe, player);
            case "hazard_check" -> hazardCheck(probe, player);
            case "local_map_summary" -> localMapSummary(probe, player);
            case "spatial_region" -> spatialRegion(probe, player);
            case "perception_snapshot" -> perceptionSnapshot(probe, player);
            default -> blocked(probe, "Probe type is not implemented.");
        };
    }

    private Map<String, Object> registryFact(Map<String, Object> probe) {
        Map<?, ?> target = target(probe);
        String registryKind = text(target.get("registry_kind"));
        String requestedResourceId = text(target.get("resource_id"));
        ResourceLocation resourceId = ResourceLocation.tryParse(
            requestedResourceId
        );
        if (resourceId == null) {
            return failed(
                probe,
                "The registry fact request did not contain a valid namespaced resource identifier."
            );
        }
        boolean registered = switch (registryKind) {
            case "block" -> BuiltInRegistries.BLOCK.containsKey(resourceId);
            case "item" -> BuiltInRegistries.ITEM.containsKey(resourceId);
            case "entity_type" -> BuiltInRegistries.ENTITY_TYPE.containsKey(
                resourceId
            );
            case "mob_effect" -> BuiltInRegistries.MOB_EFFECT.containsKey(
                resourceId
            );
            default -> false;
        };
        if (
            !Set.of("block", "item", "entity_type", "mob_effect").contains(
                registryKind
            )
        ) {
            return failed(
                probe,
                "The requested registry kind is not in the admitted registry fact set."
            );
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("game_version", server.getServerVersion());
        details.put("registry_kind", registryKind);
        details.put("requested_resource_id", requestedResourceId);
        details.put("registered", registered);
        if (registered) {
            details.put("canonical_resource_id", resourceId.toString());
        }
        return success(
            probe,
            registered
                ? "The exact resource identifier exists in the live Minecraft registry."
                : "The exact resource identifier does not exist in the live Minecraft registry.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of("confidence", 1.0, "details", details)
        );
    }

    private Map<String, Object> recipeFact(Map<String, Object> probe) {
        Map<?, ?> target = target(probe);
        String queryKind = text(target.get("query_kind"));
        String requestedResourceId = text(target.get("resource_id"));
        ResourceLocation resourceId = ResourceLocation.tryParse(
            requestedResourceId
        );
        if (resourceId == null) {
            return failed(
                probe,
                "The recipe fact request did not contain a valid namespaced resource identifier."
            );
        }
        if (!Set.of("recipe_id", "output_item_id").contains(queryKind)) {
            return failed(
                probe,
                "The requested recipe query kind is not admitted."
            );
        }
        int maxResults = boundedInteger(
            target.get("max_results"),
            8,
            1,
            16
        );
        List<RecipeHolder<?>> recipes = server
            .getRecipeManager()
            .getRecipes()
            .stream()
            .sorted(
                Comparator.comparing(holder -> holder.id().location().toString())
            )
            .toList();
        List<Map<String, Object>> matches = new ArrayList<>();
        int matchCount = 0;
        boolean outputScanComplete = true;
        for (RecipeHolder<?> holder : recipes) {
            RecipeResultItems resultItems = recipeResultItems(holder.value());
            if (
                "output_item_id".equals(queryKind) &&
                !resultItems.complete()
            ) {
                outputScanComplete = false;
            }
            boolean matchesQuery = "recipe_id".equals(queryKind)
                ? holder.id().location().equals(resourceId)
                : resultItems.itemIds().contains(resourceId.toString());
            if (!matchesQuery) continue;
            matchCount++;
            if (matches.size() >= maxResults) continue;
            Recipe<?> recipe = holder.value();
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("recipe_id", holder.id().location().toString());
            match.put(
                "recipe_type",
                String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
            );
            match.put(
                "serializer_id",
                String.valueOf(
                    BuiltInRegistries.RECIPE_SERIALIZER.getKey(
                        recipe.getSerializer()
                    )
                )
            );
            match.put("group", boundedText(recipe.group(), 160));
            match.put("result_item_ids", resultItems.itemIds());
            match.put("result_resolution_complete", resultItems.complete());
            matches.add(match);
        }
        boolean matchesComplete =
            matchCount <= maxResults &&
            ("recipe_id".equals(queryKind) || outputScanComplete);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("game_version", server.getServerVersion());
        details.put("query_kind", queryKind);
        details.put("requested_resource_id", requestedResourceId);
        details.put("match_count", matchCount);
        details.put("matches_complete", matchesComplete);
        details.put("matches", matches);
        return success(
            probe,
            matchCount == 0
                ? "No matching recipe fact was found in the live recipe manager."
                : "Live recipe facts were read from the current recipe manager.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of("confidence", 1.0, "details", details)
        );
    }

    private static RecipeResultItems recipeResultItems(Recipe<?> recipe) {
        Set<String> itemIds = new TreeSet<>();
        boolean complete = !recipe.display().isEmpty();
        for (RecipeDisplay display : recipe.display()) {
            SlotDisplay result = display.result();
            if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
                itemIds.add(
                    String.valueOf(
                        BuiltInRegistries.ITEM.getKey(
                            itemDisplay.item().value()
                        )
                    )
                );
            } else if (
                result instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay
            ) {
                itemIds.add(
                    String.valueOf(
                        BuiltInRegistries.ITEM.getKey(
                            stackDisplay.stack().getItem()
                        )
                    )
                );
            } else {
                complete = false;
            }
        }
        return new RecipeResultItems(List.copyOf(itemIds), complete);
    }

    private PlayerResolution resolvePlayer(Map<String, Object> probe) {
        String requestedActor = "";
        Object target = probe.get("target");
        if (target instanceof Map<?, ?> targetMap) {
            Object value = targetMap.get("actor_id");
            if (value != null) requestedActor = String.valueOf(value).trim();
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<ActorCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) {
            ServerPlayer player = players.get(index);
            String label = player.getGameProfile().getName();
            candidates.add(
                new ActorCandidate(
                    index,
                    canonicalActorId(label),
                    player.getUUID().toString(),
                    label
                )
            );
        }
        ActorSelection selection = selectActor(candidates, requestedActor);
        if (
            "selected".equals(selection.status()) &&
            selection.selectedIndex() != null
        ) {
            return new PlayerResolution(
                players.get(selection.selectedIndex()),
                null,
                null
            );
        }
        if ("target_ambiguous".equals(selection.status())) {
            return new PlayerResolution(
                null,
                "target_ambiguous",
                "The current actor is ambiguous because multiple players are online and no exact actor binding was supplied."
            );
        }
        return new PlayerResolution(
            null,
            "target_unavailable",
            requestedActor.isBlank()
                ? "No online player is available for the current actor."
                : "The exactly requested actor is not online in this world."
        );
    }

    static ActorSelection selectActor(
        List<ActorCandidate> candidates,
        String requestedActorId
    ) {
        String requested = requestedActorId == null
            ? ""
            : requestedActorId.trim();
        if (!requested.isEmpty()) {
            List<ActorCandidate> exact = candidates
                .stream()
                .filter(candidate ->
                    requested.equalsIgnoreCase(candidate.canonicalId()) ||
                    requested.equalsIgnoreCase(candidate.uuid()) ||
                    requested.equalsIgnoreCase(candidate.actorLabel())
                )
                .toList();
            return exact.size() == 1
                ? new ActorSelection("selected", exact.get(0).index())
                : new ActorSelection(
                    exact.size() > 1
                        ? "target_ambiguous"
                        : "target_unavailable",
                    null
                );
        }
        return switch (candidates.size()) {
            case 0 -> new ActorSelection("target_unavailable", null);
            case 1 -> new ActorSelection("selected", candidates.get(0).index());
            default -> new ActorSelection("target_ambiguous", null);
        };
    }

    private Map<String, Object> actorStatus(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        List<String> flags = new ArrayList<>();
        if (player.isSprinting()) flags.add("sprinting");
        if (player.isShiftKeyDown()) flags.add("sneaking");
        if (player.isSwimming()) flags.add("swimming");
        if (player.getAbilities().flying) flags.add("flying");
        if (player.isFallFlying()) flags.add("gliding");
        if (player.isSleeping()) flags.add("sleeping");
        if (player.isOnFire()) flags.add("on_fire");
        if (player.isFreezing()) flags.add("freezing");
        if (player.isDeadOrDying()) flags.add("dead");
        flags.addAll(FabricFallRescueController.statusFlags(player));

        List<Map<String, Object>> effects = player
            .getActiveEffects()
            .stream()
            .map(FabricProbeExecutor::effectDetails)
            .sorted(
                Comparator.comparing(effect ->
                    String.valueOf(effect.get("effect"))
                )
            )
            .toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actor_label", player.getGameProfile().getName());
        details.put("health", round(player.getHealth()));
        details.put("max_health", round(player.getMaxHealth()));
        details.put("food_level", player.getFoodData().getFoodLevel());
        details.put(
            "saturation",
            round(player.getFoodData().getSaturationLevel())
        );
        details.put(
            "game_mode",
            player.gameMode.getGameModeForPlayer().getName().toLowerCase(
                Locale.ROOT
            )
        );
        details.put("world", dimension(player));
        details.put(
            "position",
            Map.of(
                "x",
                round(player.getX()),
                "y",
                round(player.getY()),
                "z",
                round(player.getZ())
            )
        );
        details.put("yaw", round(player.getYRot()));
        details.put("pitch", round(player.getXRot()));
        Map<String, Object> lookedAtBlock = lookedAtBlock(player);
        if (!lookedAtBlock.isEmpty()) {
            details.put("looked_at_block", lookedAtBlock);
        }
        details.put("status_flags", List.copyOf(flags));
        details.put("active_effects", effects);
        Map<String, Object> mechanicsState =
            FabricMechanicsStateReader.read(server);
        if (!mechanicsState.isEmpty()) {
            details.put("mechanics_state", mechanicsState);
        }
        return success(
            probe,
            "Actor status read-only probe completed.",
            SensorScope.PLAYER_OBSERVABLE,
            Map.of("confidence", 0.98, "details", details)
        );
    }

    private Map<String, Object> lookedAtBlock(ServerPlayer player) {
        HitResult hit = player.pick(6.0d, 0.0f, false);
        if (
            hit.getType() != HitResult.Type.BLOCK ||
            !(hit instanceof BlockHitResult blockHit)
        ) return Map.of();
        BlockPos position = blockHit.getBlockPos();
        BlockState state = serverLevel(player).getBlockState(position);
        Vec3 aim = Vec3.atCenterOf(position);
        double distance = player.getEyePosition().distanceTo(hit.getLocation());
        return Map.of(
            "block_id",
            blockId(state),
            "position",
            Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ()),
            "aim_position",
            Map.of("x", round(aim.x), "y", round(aim.y), "z", round(aim.z)),
            "distance_blocks",
            round(distance),
            "within_interaction_range",
            distance <= 4.5d
        );
    }

    private static Map<String, Object> effectDetails(MobEffectInstance effect) {
        String effectId = String.valueOf(
            BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value())
        );
        return Map.of(
            "effect",
            effectId,
            "amplifier",
            effect.getAmplifier(),
            "duration_ticks",
            effect.getDuration()
        );
    }

    private Map<String, Object> inventoryCheck(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        List<Map<String, Object>> slots = new ArrayList<>();
        int foodStacks = 0;
        int size = Math.min(
            player.getInventory().getContainerSize(),
            config.snapshotOptions().maxInventoryStacks()
        );
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            boolean edible = stack.has(DataComponents.FOOD);
            if (edible) foodStacks++;
            slots.add(stackDetails(stack, slot));
        }
        List<Map<String, Object>> equipment = new ArrayList<>();
        for (
            EquipmentSlot slot : List.of(
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND
            )
        ) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>(
                stackDetails(stack, slot.getIndex())
            );
            item.put("equipment_slot", slot.getName());
            equipment.add(item);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stack_count", slots.size());
        details.put("food_stack_count", foodStacks);
        details.put("slots", slots);
        details.put("equipment", equipment);
        return success(
            probe,
            "Inventory read-only check completed.",
            SensorScope.PLAYER_OBSERVABLE,
            Map.of("confidence", 0.96, "details", details)
        );
    }

    private static Map<String, Object> stackDetails(
        ItemStack stack,
        int slot
    ) {
        return Map.of(
            "slot",
            slot,
            "item",
            String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())),
            "item_label",
            stack.getHoverName().getString(),
            "count",
            stack.getCount()
        );
    }

    private Map<String, Object> nearbyEntities(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        ServerLevel level = serverLevel(player);
        double radius = config.snapshotOptions().nearbyEntityRadius();
        List<Map<String, Object>> entities = level
            .getEntities(
                player,
                player.getBoundingBox().inflate(radius, radius / 2.0d, radius),
                entity -> entity != player
            )
            .stream()
            .sorted(Comparator.comparingDouble(player::distanceToSqr))
            .limit(config.snapshotOptions().maxEntities())
            .map(entity -> nearbyEntityDetails(entity, player))
            .toList();
        return success(
            probe,
            "Nearby entity read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of(
                "confidence",
                0.92,
                "details",
                Map.of("entity_count", entities.size(), "entities", entities)
            )
        );
    }

    private static Map<String, Object> nearbyEntityDetails(
        Entity entity,
        ServerPlayer actor
    ) {
        String classification;
        if (entity instanceof Monster) classification = "hostile";
        else if (entity instanceof ServerPlayer) classification = "player";
        else if (entity instanceof Projectile) classification = "projectile";
        else if (entity instanceof LivingEntity) classification = "passive";
        else classification = "other";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
            "entity_type",
            String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
        );
        details.put("entity_label", entity.getName().getString());
        details.put("classification", classification);
        details.put(
            "distance_blocks",
            round(Math.sqrt(entity.distanceToSqr(actor)))
        );
        details.put(
            "targeting_actor",
            entity instanceof Monster monster && actor.equals(monster.getTarget())
        );
        if (entity instanceof LivingEntity living) {
            details.put("health", round(living.getHealth()));
            details.put("max_health", round(living.getMaxHealth()));
        }
        details.put(
            "scoreboard_tags",
            entity.getTags().stream().sorted().limit(16).toList()
        );
        return details;
    }

    private Map<String, Object> lineOfSight(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Vec3 target = targetPosition(probe, player);
        if (target == null) return failed(probe, "Probe target position is missing.");
        Vec3 eye = player.getEyePosition();
        HitResult hit = serverLevel(player)
            .clip(
                new ClipContext(
                    eye,
                    target,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player
                )
            );
        boolean clear =
            hit.getType() == HitResult.Type.MISS ||
            hit.getLocation().distanceTo(target) < 1.5d;
        return success(
            probe,
            "Line-of-sight read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of(
                "line_of_sight",
                clear,
                "distance_blocks",
                round(eye.distanceTo(target)),
                "confidence",
                0.88
            )
        );
    }

    private Map<String, Object> reachability(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Vec3 target = targetPosition(probe, player);
        if (target == null) return failed(probe, "Probe target position is missing.");
        double distance = player.position().distanceTo(target);
        boolean withinBound = distance <= config.probeOptions().maxRouteRadius();
        return success(
            probe,
            "Reachability bounded-distance read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            reachabilityResult(distance, withinBound)
        );
    }

    static Map<String, Object> reachabilityResult(
        double distance,
        boolean withinBound
    ) {
        return Map.of(
            "feasible",
            withinBound,
            "reachable",
            distance <= 5.0d,
            "distance_blocks",
            round(distance),
            "confidence",
            withinBound ? 0.72 : 0.4
        );
    }

    private Map<String, Object> cropState(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        BlockPos position = targetBlockPosition(probe, player);
        if (position == null) {
            HitResult hit = player.pick(6.0d, 0.0f, false);
            if (hit instanceof BlockHitResult blockHit) {
                position = blockHit.getBlockPos();
            }
        }
        if (position == null) return failed(probe, "Target block is missing.");
        BlockState state = serverLevel(player).getBlockState(position);
        IntegerProperty age = state
            .getProperties()
            .stream()
            .filter(property ->
                property instanceof IntegerProperty &&
                "age".equals(property.getName())
            )
            .map(property -> (IntegerProperty) property)
            .findFirst()
            .orElse(null);
        if (age == null) return failed(probe, "Target block is not an age-based crop.");
        int currentAge = state.getValue(age);
        int maximumAge = age.getPossibleValues().stream().max(Integer::compare).orElse(0);
        return success(
            probe,
            "Crop state read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of(
                "crop_mature",
                currentAge >= maximumAge,
                "confidence",
                0.92,
                "details",
                Map.of(
                    "block_type",
                    blockId(state),
                    "age",
                    currentAge,
                    "maximum_age",
                    maximumAge
                )
            )
        );
    }

    private Map<String, Object> hazardCheck(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        ServerLevel level = serverLevel(player);
        List<Double> hostileDistances = level
            .getEntities(
                player,
                player.getBoundingBox().inflate(16.0d, 8.0d, 16.0d),
                entity -> entity instanceof Monster
            )
            .stream()
            .map(entity -> Math.sqrt(entity.distanceToSqr(player)))
            .sorted()
            .toList();
        int hazardCount = 0;
        double nearestHazard = Double.POSITIVE_INFINITY;
        Set<String> hazardTypes = new TreeSet<>();
        BlockPos origin = player.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos position = origin.offset(dx, dy, dz);
                    String type = hazardType(level.getBlockState(position));
                    if (type == null) continue;
                    hazardCount++;
                    hazardTypes.add(type);
                    nearestHazard = Math.min(
                        nearestHazard,
                        Vec3.atCenterOf(position).distanceTo(player.position())
                    );
                }
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hostile_entity_count", hostileDistances.size());
        if (!hostileDistances.isEmpty()) {
            details.put(
                "nearest_hostile_distance_blocks",
                round(hostileDistances.get(0))
            );
        }
        details.put("environmental_hazard_block_count", hazardCount);
        details.put(
            "environmental_hazard_types",
            List.copyOf(hazardTypes)
        );
        if (Double.isFinite(nearestHazard)) {
            details.put(
                "nearest_environmental_hazard_distance_blocks",
                round(nearestHazard)
            );
        }
        details.put("actor_on_fire", player.isOnFire());
        details.put("actor_freezing", player.isFreezing());
        boolean present =
            !hostileDistances.isEmpty() ||
            hazardCount > 0 ||
            player.isOnFire() ||
            player.isFreezing();
        return success(
            probe,
            "Hazard check read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of(
                "hazard_present",
                present,
                "confidence",
                0.84,
                "details",
                details
            )
        );
    }

    private Map<String, Object> localMapSummary(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        int sampled = 0;
        int solid = 0;
        int hazardous = 0;
        int liquid = 0;
        ServerLevel level = serverLevel(player);
        BlockPos origin = player.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                sampled++;
                BlockState state = level.getBlockState(origin.offset(dx, -1, dz));
                if (!state.isAir()) solid++;
                if (!state.getFluidState().isEmpty()) liquid++;
                if (hazardType(state) != null) hazardous++;
            }
        }
        return success(
            probe,
            "Local map summary read-only probe completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of(
                "confidence",
                0.82,
                "details",
                Map.of(
                    "sampled_floor_blocks",
                    sampled,
                    "solid_floor_blocks",
                    solid,
                    "open_floor_blocks",
                    sampled - solid,
                    "hazardous_floor_blocks",
                    hazardous,
                    "liquid_floor_blocks",
                    liquid
                )
            )
        );
    }

    private Map<String, Object> spatialRegion(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Object rawTarget = probe.get("target");
        Map<?, ?> target = rawTarget instanceof Map<?, ?> map
            ? map
            : Map.of();
        int horizontalRadius = boundedInteger(
            target.get("horizontal_radius"),
            FabricSpatialSurvey.DEFAULT_HORIZONTAL_RADIUS,
            1,
            FabricSpatialSurvey.MAX_HORIZONTAL_RADIUS
        );
        int verticalRadius = boundedInteger(
            target.get("vertical_radius"),
            FabricSpatialSurvey.DEFAULT_VERTICAL_RADIUS,
            1,
            FabricSpatialSurvey.MAX_VERTICAL_RADIUS
        );
        String purpose = target.get("purpose") instanceof String text
            ? text.trim()
            : "general";
        Integer requestedLength = optionalBoundedInteger(
            target.get("requested_length"),
            3,
            15
        );
        Integer requestedHeight = optionalBoundedInteger(
            target.get("requested_height"),
            3,
            8
        );
        String requestedOrientation = switch (
            String.valueOf(target.get("orientation"))
        ) {
            case "north_south", "east_west" ->
                String.valueOf(target.get("orientation"));
            default -> null;
        };
        String requestedRelativeSide = switch (
            String.valueOf(target.get("relative_side"))
        ) {
            case "north", "south", "east", "west" ->
                String.valueOf(target.get("relative_side"));
            default -> null;
        };
        BlockPos verificationFrom = exactBlockPosition(
            target.get("verification_from")
        );
        BlockPos verificationTo = exactBlockPosition(
            target.get("verification_to")
        );
        String expectedBlock = target.get("expected_block") instanceof String text
            ? text.trim()
            : null;
        Map<String, Object> details = FabricSpatialSurvey.inspect(
            serverLevel(player),
            player.blockPosition(),
            horizontalRadius,
            verticalRadius,
            purpose,
            requestedLength,
            requestedHeight,
            requestedOrientation,
            requestedRelativeSide,
            player.getYRot(),
            verificationFrom,
            verificationTo,
            expectedBlock
        );
        return success(
            probe,
            spatialRegionSummary(purpose, details),
            SensorScope.SENSOR_OBSERVABLE,
            Map.of("confidence", 0.95, "details", details)
        );
    }

    private Map<String, Object> perceptionSnapshot(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Map<?, ?> target = target(probe);
        int horizontalRadius = boundedInteger(
            target.get("horizontal_radius"),
            4,
            1,
            7
        );
        int verticalRadius = boundedInteger(
            target.get("vertical_radius"),
            8,
            2,
            16
        );
        Map<String, Object> details = FabricPerceptionSnapshot.capture(
            serverLevel(player),
            player,
            config,
            horizontalRadius,
            verticalRadius,
            Boolean.TRUE.equals(target.get("include_navigation_collision"))
        );
        return success(
            probe,
            "Bounded same-revision Minecraft perception snapshot completed.",
            SensorScope.SENSOR_OBSERVABLE,
            Map.of("confidence", 0.95, "details", details)
        );
    }

    static String spatialRegionSummary(
        String purpose,
        Map<String, Object> details
    ) {
        if ("movement_safety".equals(purpose)) {
            Object rawCandidates = details.get("walk_step_candidates");
            int safeCandidateCount = rawCandidates instanceof List<?> candidates
                ? (int) candidates
                    .stream()
                    .filter(candidate ->
                        candidate instanceof Map<?, ?> row &&
                        Boolean.TRUE.equals(row.get("safe_candidate"))
                    )
                    .count()
                : 0;
            if (safeCandidateCount == 0) {
                return "No conservative one-block movement candidate was " +
                    "verified in this bounded spatial survey. Do not move; " +
                    "report the missing or unsafe evidence.";
            }
            return "Bounded spatial-region read-only probe verified " +
                safeCandidateCount +
                " conservative one-block movement candidate(s). Use only a " +
                "walk_step_candidates relative_direction whose " +
                "safe_candidate value is true.";
        }
        if ("structure_verification".equals(purpose)) {
            Object rawVerification = details.get("target_geometry_verification");
            if (!(rawVerification instanceof Map<?, ?> verification)) {
                return "No exact target geometry verification was produced. " +
                    "Do not claim the requested structure was verified.";
            }
            boolean complete = Boolean.TRUE.equals(verification.get("complete"));
            boolean allMatch = Boolean.TRUE.equals(verification.get("all_match"));
            int totalCells = verification.get("total_cells") instanceof Number number
                ? number.intValue()
                : 0;
            int mismatchedCells = verification.get("mismatched_cells") instanceof Number number
                ? number.intValue()
                : 0;
            if (complete && allMatch && totalCells > 0) {
                return "Exact post-action geometry verification confirmed all " +
                    totalCells + " requested cells match " +
                    String.valueOf(verification.get("expected_block")) +
                    " between the inclusive from/to coordinates.";
            }
            if (!complete) {
                return "Exact post-action geometry verification was incomplete " +
                    "or outside the bounded actor-centered survey. Do not claim " +
                    "the requested structure was verified.";
            }
            return "Exact post-action geometry verification found " +
                mismatchedCells + " mismatched cell(s). Do not claim the " +
                "requested structure was verified; use the mismatch samples " +
                "for bounded repair or report the mismatch.";
        }
        if (
            "build_planning".equals(purpose) ||
            "structure_planning".equals(purpose)
        ) {
            Object rawCandidates = details.get("build_line_candidates");
            int candidateCount = rawCandidates instanceof List<?> candidates
                ? candidates.size()
                : 0;
            if (candidateCount == 0) {
                return "No strictly air-filled build-line candidate was " +
                "verified for the requested geometry and side in this bounded " +
                "spatial survey. Do not infer exact " +
                "build endpoints from compact columns; report that no safe " +
                "candidate was verified or request another bounded survey.";
            }
            return "Bounded spatial-region read-only probe verified " +
            candidateCount +
            " conservative strict-air build-line candidate(s) matching the " +
            "requested geometry and side. For air-only " +
            "construction, use only build_line_candidates from/to coordinates " +
            "and do not infer alternative endpoints from compact columns.";
        }
        return "Bounded spatial-region read-only probe completed.";
    }

    static String hazardType(BlockState state) {
        if (state.is(Blocks.LAVA)) return "lava";
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return "fire";
        if (state.is(Blocks.MAGMA_BLOCK)) return "magma_block";
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            return "campfire";
        }
        if (state.is(Blocks.CACTUS)) return "cactus";
        if (state.is(Blocks.POWDER_SNOW)) return "powder_snow";
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry_bush";
        if (state.is(Blocks.WITHER_ROSE)) return "wither_rose";
        if (state.is(Blocks.POINTED_DRIPSTONE)) return "pointed_dripstone";
        return null;
    }

    private Vec3 targetPosition(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Object target = probe.get("target");
        if (!(target instanceof Map<?, ?> targetMap)) return null;
        Object position = targetMap.get("position");
        if (!(position instanceof Map<?, ?> pos)) return null;
        Number x = number(pos.get("x"));
        Number y = number(pos.get("y"));
        Number z = number(pos.get("z"));
        if (x == null || y == null) return null;
        return new Vec3(
            x.doubleValue(),
            y.doubleValue(),
            z == null ? player.getZ() : z.doubleValue()
        );
    }

    private BlockPos targetBlockPosition(
        Map<String, Object> probe,
        ServerPlayer player
    ) {
        Vec3 target = targetPosition(probe, player);
        return target == null
            ? null
            : BlockPos.containing(target.x, target.y, target.z);
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static Map<?, ?> target(Map<String, Object> probe) {
        Object value = probe.get("target");
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String boundedText(String value, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximumLength
            ? normalized
            : normalized.substring(0, maximumLength);
    }

    private static int boundedInteger(
        Object value,
        int fallback,
        int minimum,
        int maximum
    ) {
        int parsed = value instanceof Number number
            ? number.intValue()
            : fallback;
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static Integer optionalBoundedInteger(
        Object value,
        int minimum,
        int maximum
    ) {
        if (!(value instanceof Number number)) return null;
        int parsed = number.intValue();
        return parsed >= minimum && parsed <= maximum ? parsed : null;
    }

    private static BlockPos exactBlockPosition(Object value) {
        if (!(value instanceof Map<?, ?> position)) return null;
        Number x = number(position.get("x"));
        Number y = number(position.get("y"));
        Number z = number(position.get("z"));
        if (x == null || y == null || z == null) return null;
        double exactX = x.doubleValue();
        double exactY = y.doubleValue();
        double exactZ = z.doubleValue();
        if (
            !Double.isFinite(exactX) ||
            !Double.isFinite(exactY) ||
            !Double.isFinite(exactZ) ||
            Math.rint(exactX) != exactX ||
            Math.rint(exactY) != exactY ||
            Math.rint(exactZ) != exactZ
        ) {
            return null;
        }
        return new BlockPos((int) exactX, (int) exactY, (int) exactZ);
    }

    private Map<String, Object> success(
        Map<String, Object> probe,
        String summary,
        SensorScope scope,
        Map<String, Object> result
    ) {
        return base(probe, "succeeded", summary, scope, result);
    }

    private Map<String, Object> failed(
        Map<String, Object> probe,
        String summary
    ) {
        return base(
            probe,
            "failed",
            summary,
            SensorScope.UNKNOWN,
            Map.of("confidence", 0.2)
        );
    }

    private Map<String, Object> failed(
        Map<String, Object> probe,
        String summary,
        String failureCode
    ) {
        return base(
            probe,
            "failed",
            summary,
            SensorScope.UNKNOWN,
            Map.of(
                "confidence",
                0.2,
                "details",
                Map.of("failure_code", failureCode)
            )
        );
    }

    private Map<String, Object> blocked(
        Map<String, Object> probe,
        String summary
    ) {
        return base(
            probe,
            "blocked_by_policy",
            summary,
            SensorScope.UNKNOWN,
            Map.of()
        );
    }

    private Map<String, Object> base(
        Map<String, Object> probe,
        String status,
        String summary,
        SensorScope scope,
        Map<String, Object> result
    ) {
        runtimeStatus.recordProbeSummary(
            String.valueOf(probe.get("probe_type")),
            status
        );
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema", "helix.environment_probe_result.v1");
        output.put(
            "probe_result_id",
            "environment_probe_result:" +
            probe.get("probe_request_id") +
            ":" +
            status
        );
        output.put(
            "probe_request_id",
            String.valueOf(probe.get("probe_request_id"))
        );
        output.put("source_id", config.sourceId());
        output.put("room_id", config.roomId());
        output.put("domain", "minecraft");
        output.put("probe_type", String.valueOf(probe.get("probe_type")));
        output.put("status", status);
        output.put("result_summary", summary);
        output.put("result", result);
        output.put("sensor_scope", scope.wireValue());
        output.put(
            "requires_caveat",
            config.sensorScopePolicy().requiresCaveat(scope)
        );
        output.put("side_effects_performed", false);
        output.put("commands_executed", List.of());
        output.put("world_mutation_performed", false);
        output.put(
            "evidence_refs",
            List.of("minecraft:fabric:probe:" + probe.get("probe_request_id"))
        );
        output.put("deterministic", true);
        output.put("model_invoked", false);
        output.put("assistant_answer", false);
        output.put("raw_content_included", false);
        output.put("context_policy", "compact_context_pack_only");
        output.put("created_at", Instant.now().toString());
        return output;
    }

    static String canonicalActorId(String label) {
        return "minecraft:player:" + label;
    }

    static String dimension(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    static ServerLevel serverLevel(ServerPlayer player) {
        return (ServerLevel) player.level();
    }

    static String blockId(BlockState state) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
