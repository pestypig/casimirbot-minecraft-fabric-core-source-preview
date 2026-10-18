package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.combat.ProjectileThreatForecaster;
import com.casimirbot.helixsensor.navigation.BoundedNavigationFrontier;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds one bounded tactical observation on the Minecraft server thread. All
 * mechanics fields share one game tick. Client-only screen/manual-input facts
 * stay explicitly unobserved until the player companion supplies them.
 */
final class FabricPerceptionSnapshot {
    static final String SCHEMA = "helix.minecraft_perception_snapshot.v1";
    private static final int MAX_HAZARDS = 128;
    private static final int MAX_PROJECTILES = 128;
    private static final int PROJECTILE_SUPPORT_TICKS = 20;
    private static final double PROJECTILE_NEAR_MISS_MARGIN = 0.75d;
    private static final int DROP_SCAN_DEPTH = 6;
    private static final long MAX_CLIENT_UI_AGE_TICKS = 10L;

    private FabricPerceptionSnapshot() {}

    static Map<String, Object> capture(
        ServerLevel level,
        ServerPlayer player,
        HelixSensorConfig config,
        int requestedHorizontalRadius,
        int requestedVerticalRadius
    ) {
        return capture(level, player, config, requestedHorizontalRadius, requestedVerticalRadius, false);
    }

    static Map<String, Object> capture(
        ServerLevel level, ServerPlayer player, HelixSensorConfig config,
        int requestedHorizontalRadius, int requestedVerticalRadius, boolean includeNavigationCollision
    ) {
        long started = System.nanoTime();
        long gameTick = level.getGameTime();
        int horizontalRadius = clamp(requestedHorizontalRadius, 1, 7);
        int verticalRadius = clamp(requestedVerticalRadius, 2, 16);
        Vec3 actorVelocity = player.getDeltaMovement();

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("position", position(player.position()));
        actor.put("velocity", position(actorVelocity));
        actor.put("yaw", round(player.getYRot()));
        actor.put("pitch", round(player.getXRot()));
        actor.put("health", round(player.getHealth()));
        actor.put("max_health", round(player.getMaxHealth()));
        actor.put("food_level", player.getFoodData().getFoodLevel());
        actor.put("air", player.getAirSupply());
        actor.put("on_ground", player.onGround());
        actor.put("on_fire", player.isOnFire());
        actor.put("freezing", player.isFreezing());

        Map<String, Object> focus = focus(level, player);
        EntityCollection entityCollection = entities(level, player, config);
        ProjectileCollection projectileCollection = projectiles(
            level,
            player,
            config
        );
        HazardCollection hazardCollection = hazards(
            level,
            player,
            horizontalRadius,
            verticalRadius
        );
        List<Map<String, Object>> movementCandidates = movementCandidates(
            level,
            player
        );
        Map<String, Object> navigationFrontier = navigationFrontier(
            level,
            player,
            horizontalRadius,
            verticalRadius
        );
        Map<String, Object> inventory = inventory(player, config);
        Coverage coverage = coverage(
            level,
            player.blockPosition(),
            horizontalRadius,
            verticalRadius
        );
        Map<String, Object> coverageMap = new LinkedHashMap<>();
        coverageMap.put("horizontal_radius", horizontalRadius);
        coverageMap.put("vertical_radius", verticalRadius);
        coverageMap.put("loaded_region_complete", coverage.unknownCells() == 0);
        coverageMap.put("unknown_cell_count", coverage.unknownCells());
        coverageMap.put("entities_complete", entityCollection.complete());
        coverageMap.put(
            "projectiles_complete",
            projectileCollection.complete()
        );
        coverageMap.put("hazards_complete", hazardCollection.complete());
        FabricClientPerceptionBridge.Snapshot clientSnapshot =
            FabricClientPerceptionBridge.fresh(
                player.getUUID(),
                gameTick,
                MAX_CLIENT_UI_AGE_TICKS
            );
        coverageMap.put(
            "omitted_categories",
            clientSnapshot == null
                ? List.of("client_screen", "manual_input_attribution")
                : List.of("manual_input_attribution")
        );
        Map<String, Object> uiState = new LinkedHashMap<>();
        uiState.put("server_container_open", player.containerMenu != player.inventoryMenu);
        uiState.put(
            "same_revision",
            clientSnapshot != null && clientSnapshot.gameTick() == gameTick
        );
        uiState.put(
            "client_screen_state",
            clientSnapshot == null ? "unobserved" : clientSnapshot.screenState()
        );
        uiState.put("input_capture_known", clientSnapshot != null);
        uiState.put(
            "input_activity",
            clientSnapshot != null && clientSnapshot.inputActivity()
        );
        if (clientSnapshot != null) {
            uiState.put("client_game_tick", clientSnapshot.gameTick());
            uiState.put(
                "server_received_tick",
                clientSnapshot.receivedServerTick()
            );
            uiState.put(
                "age_ticks",
                Math.max(0L, gameTick - clientSnapshot.receivedServerTick())
            );
            uiState.put("freshness", "fresh");
            uiState.put("screen_kind", clientSnapshot.screenKind());
        } else {
            uiState.put("freshness", "unobserved");
        }

        Map<String, Object> worldRules = new LinkedHashMap<>();
        worldRules.put(
            "keep_inventory",
            level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)
        );

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("dimension", dimension(player));
        semantic.put("actor", actor);
        semantic.put("focus", focus);
        semantic.put("entities", entityCollection.entities());
        semantic.put("projectiles", projectileCollection.projectiles());
        semantic.put("hazards", hazardCollection.hazards());
        semantic.put("movement_candidates", movementCandidates);
        semantic.put("navigation_frontier", navigationFrontier);
        semantic.put("inventory", inventory);
        semantic.put("coverage", coverageMap);
        semantic.put("ui_state", uiState);
        semantic.put("world_rules", worldRules);
        Map<String, Object> navigationCollision = FabricNavigationCollisionObservation.capture(
            includeNavigationCollision, gameTick,
            () -> FabricNavigationCollisionCapture.capture(level, player,
                FabricNavigationCollisionObservation.RADIUS, FabricNavigationCollisionObservation.RADIUS)
        );
        if (navigationCollision != null) semantic.put("navigation_collision", navigationCollision);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshot_schema", SCHEMA);
        details.put("observation_revision", gameTick);
        details.put("game_tick", gameTick);
        details.put("dimension", dimension(player));
        details.put("actor", actor);
        details.put("focus", focus);
        details.put("entities", entityCollection.entities());
        details.put("projectiles", projectileCollection.projectiles());
        details.put("hazards", hazardCollection.hazards());
        details.put("movement_candidates", movementCandidates);
        details.put("navigation_frontier", navigationFrontier);
        details.put("inventory", inventory);
        details.put("coverage", coverageMap);
        details.put("ui_state", uiState);
        details.put("world_rules", worldRules);
        if (navigationCollision != null) details.put("navigation_collision", navigationCollision);
        details.put("semantic_fingerprint", SectionHasher.hash(semantic));
        details.put(
            "capture_duration_ms",
            round((System.nanoTime() - started) / 1_000_000.0d)
        );
        return details;
    }

    private static Map<String, Object> focus(
        ServerLevel level,
        ServerPlayer player
    ) {
        HitResult hit = player.pick(6.0d, 0.0f, false);
        Map<String, Object> focus = new LinkedHashMap<>();
        if (!(hit instanceof BlockHitResult blockHit)) {
            focus.put("kind", "miss");
            focus.put("distance_blocks", 0.0d);
            focus.put("line_of_sight", true);
            focus.put("occlusion", "none");
            return focus;
        }
        BlockPos blockPosition = blockHit.getBlockPos();
        Vec3 aim = Vec3.atCenterOf(blockPosition);
        focus.put("kind", "block");
        focus.put("distance_blocks", round(player.getEyePosition().distanceTo(hit.getLocation())));
        focus.put("line_of_sight", true);
        focus.put("occlusion", "none");
        focus.put("block_id", blockId(level.getBlockState(blockPosition)));
        focus.put("position", position(blockPosition));
        focus.put("aim_position", position(aim));
        return focus;
    }

    private static EntityCollection entities(
        ServerLevel level,
        ServerPlayer player,
        HelixSensorConfig config
    ) {
        double radius = config.snapshotOptions().nearbyEntityRadius();
        List<Entity> all = level.getEntities(
            player,
            player.getBoundingBox().inflate(radius, radius / 2.0d, radius),
            entity -> entity != player
        ).stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        int limit = config.snapshotOptions().maxEntities();
        List<Map<String, Object>> rows = all.stream()
            .limit(limit)
            .map(entity -> entity(level, player, entity))
            .toList();
        return new EntityCollection(rows, all.size() <= limit);
    }

    private static Map<String, Object> entity(
        ServerLevel level,
        ServerPlayer actor,
        Entity entity
    ) {
        Vec3 offset = entity.position().subtract(actor.position());
        double distance = offset.length();
        Vec3 relativeVelocity = entity.getDeltaMovement().subtract(actor.getDeltaMovement());
        double closingSpeed = distance > 0.0001d
            ? -relativeVelocity.dot(offset.scale(1.0d / distance)) * 20.0d
            : 0.0d;
        Vec3 target = entity instanceof LivingEntity living
            ? living.getEyePosition()
            : entity.getBoundingBox().getCenter();
        HitResult hit = level.clip(new ClipContext(
            actor.getEyePosition(),
            target,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            actor
        ));
        boolean clear = hit.getType() == HitResult.Type.MISS ||
            hit.getLocation().distanceTo(target) < 0.75d;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entity_type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        row.put("classification", classification(entity));
        row.put("distance_blocks", round(distance));
        row.put("bearing_degrees", bearing(actor.getYRot(), offset.x, offset.z));
        row.put("relative_elevation_blocks", round(offset.y));
        row.put("closing_speed_blocks_per_second", round(closingSpeed));
        row.put(
            "targeting_actor",
            entity instanceof Monster monster && actor.equals(monster.getTarget())
        );
        row.put("line_of_sight", clear);
        row.put("occlusion", clear ? "none" : "block");
        return row;
    }

    private static ProjectileCollection projectiles(
        ServerLevel level,
        ServerPlayer player,
        HelixSensorConfig config
    ) {
        double radius = config.snapshotOptions().nearbyEntityRadius();
        List<Projectile> all = level.getEntitiesOfClass(
            Projectile.class,
            player.getBoundingBox().inflate(radius, radius / 2.0d, radius),
            projectile -> projectile.isAlive()
        ).stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        List<Map<String, Object>> rows = all.stream()
            .limit(MAX_PROJECTILES)
            .map(projectile -> projectile(level, player, projectile))
            .toList();
        return new ProjectileCollection(rows, all.size() <= MAX_PROJECTILES);
    }

    private static Map<String, Object> projectile(
        ServerLevel level,
        ServerPlayer actor,
        Projectile projectile
    ) {
        boolean arrowPhysicsSupported = projectile instanceof AbstractArrow &&
            !projectile.isInWater();
        double drag = 0.99d;
        Vec3 acceleration = arrowPhysicsSupported
            ? new Vec3(0, projectile.isNoGravity() ? 0 : -0.05d, 0)
            : Vec3.ZERO;
        ProjectileThreatForecaster.Input provisionalInput = projectileInput(
            projectile,
            actor,
            acceleration,
            drag,
            arrowPhysicsSupported,
            null
        );
        ProjectileThreatForecaster.Forecast provisional =
            ProjectileThreatForecaster.forecast(provisionalInput);
        TrajectoryCoverage trajectoryCoverage = trajectoryCoverage(
            level,
            actor,
            provisional.samples()
        );
        boolean evidenceComplete = arrowPhysicsSupported &&
            trajectoryCoverage.complete();
        ProjectileThreatForecaster.Forecast forecast =
            ProjectileThreatForecaster.forecast(projectileInput(
                projectile,
                actor,
                acceleration,
                drag,
                evidenceComplete,
                trajectoryCoverage.occlusionTick()
            ));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectile_ref", opaqueEntityRef("projectile", projectile));
        row.put("incarnation_ref", opaqueEntityRef("projectile_incarnation", projectile));
        row.put(
            "projectile_type_id",
            String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()))
        );
        if (projectile.getOwner() != null) {
            row.put(
                "owner_ref",
                opaqueEntityRef("projectile_owner", projectile.getOwner())
            );
        }
        row.put("position", position(projectile.position()));
        row.put("velocity", position(projectile.getDeltaMovement()));
        row.put("acceleration", position(acceleration));
        row.put("support_ticks", PROJECTILE_SUPPORT_TICKS);
        if (forecast.predictedCollisionTick() != null) {
            row.put("predicted_collision_tick", forecast.predictedCollisionTick());
        }
        if (forecast.predictedImpactPosition() != null) {
            row.put(
                "predicted_impact_position",
                position(forecast.predictedImpactPosition())
            );
        }
        row.put(
            "threat_classification",
            forecast.classification().name().toLowerCase(java.util.Locale.ROOT)
        );
        row.put("evidence_complete", forecast.evidenceComplete());
        row.put("occluded", forecast.occluded());
        return row;
    }

    private static ProjectileThreatForecaster.Input projectileInput(
        Projectile projectile,
        ServerPlayer actor,
        Vec3 acceleration,
        double drag,
        boolean evidenceComplete,
        Integer verifiedOcclusionTick
    ) {
        net.minecraft.world.phys.AABB box = actor.getBoundingBox();
        return new ProjectileThreatForecaster.Input(
            vector(projectile.position()),
            vector(projectile.getDeltaMovement()),
            vector(acceleration),
            drag,
            PROJECTILE_SUPPORT_TICKS,
            new ProjectileThreatForecaster.Box(
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ
            ),
            PROJECTILE_NEAR_MISS_MARGIN,
            evidenceComplete,
            verifiedOcclusionTick
        );
    }

    private static TrajectoryCoverage trajectoryCoverage(
        ServerLevel level,
        ServerPlayer actor,
        List<ProjectileThreatForecaster.Sample> samples
    ) {
        for (int index = 1; index < samples.size(); index++) {
            Vec3 start = vector(samples.get(index - 1).position());
            Vec3 end = vector(samples.get(index).position());
            if (
                !level.hasChunkAt(BlockPos.containing(start)) ||
                !level.hasChunkAt(BlockPos.containing(end))
            ) {
                return new TrajectoryCoverage(false, null);
            }
            HitResult hit = level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                actor
            ));
            if (hit.getType() != HitResult.Type.MISS) {
                return new TrajectoryCoverage(true, index);
            }
        }
        return new TrajectoryCoverage(true, null);
    }

    private static String opaqueEntityRef(String kind, Entity entity) {
        return kind + ":" + SectionHasher.hash(Map.of(
            "kind", kind,
            "entity_uuid", entity.getUUID().toString(),
            "entity_id", entity.getId()
        ));
    }

    private static HazardCollection hazards(
        ServerLevel level,
        ServerPlayer player,
        int horizontalRadius,
        int verticalRadius
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int fullCount = 0;
        BlockPos origin = player.blockPosition();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -Math.min(2, verticalRadius); dy <= Math.min(3, verticalRadius); dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos position = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(position)) continue;
                    String kind = hazardType(level.getBlockState(position));
                    if (kind == null) continue;
                    fullCount++;
                    if (rows.size() >= MAX_HAZARDS) continue;
                    Vec3 offset = Vec3.atCenterOf(position).subtract(player.position());
                    rows.add(Map.of(
                        "kind", kind,
                        "position", position(position),
                        "distance_blocks", round(offset.length()),
                        "bearing_degrees", bearing(player.getYRot(), offset.x, offset.z),
                        "critical", offset.length() <= 2.25d || "lava".equals(kind) || "fire".equals(kind)
                    ));
                }
            }
        }
        rows.sort(Comparator.comparingDouble(row -> ((Number) row.get("distance_blocks")).doubleValue()));
        return new HazardCollection(List.copyOf(rows), fullCount <= MAX_HAZARDS);
    }

    private static List<Map<String, Object>> movementCandidates(
        ServerLevel level,
        ServerPlayer player
    ) {
        String[] cardinals = {"south", "west", "north", "east"};
        int[] dx = {0, -1, 0, 1};
        int[] dz = {1, 0, -1, 0};
        int facing = Math.floorMod(Math.round(player.getYRot() / 90.0F), 4);
        BlockPos origin = player.blockPosition();
        List<Map<String, Object>> rows = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            BlockPos feetPosition = origin.offset(dx[index], 0, dz[index]);
            BlockPos headPosition = feetPosition.above();
            boolean loaded = level.hasChunkAt(feetPosition) && level.hasChunkAt(headPosition);
            BlockState feet = loaded ? level.getBlockState(feetPosition) : Blocks.BEDROCK.defaultBlockState();
            BlockState head = loaded ? level.getBlockState(headPosition) : Blocks.BEDROCK.defaultBlockState();
            boolean feetClear = loaded && clearance(feet);
            boolean headClear = loaded && clearance(head);
            BlockPos supportPosition = feetPosition.below();
            BlockState support = Blocks.AIR.defaultBlockState();
            int dropDepth = 7;
            boolean dropComplete = false;
            for (int depth = 0; depth <= DROP_SCAN_DEPTH; depth++) {
                BlockPos candidate = feetPosition.below(depth + 1);
                if (!level.hasChunkAt(candidate)) break;
                BlockState candidateState = level.getBlockState(candidate);
                if (!clearance(candidateState)) {
                    supportPosition = candidate;
                    support = candidateState;
                    dropDepth = depth;
                    dropComplete = true;
                    break;
                }
            }
            int nearbyHazards = 0;
            int nearbyFluids = 0;
            boolean neighborhoodComplete = loaded;
            for (int nx = -1; nx <= 1; nx++) {
                for (int ny = -1; ny <= 2; ny++) {
                    for (int nz = -1; nz <= 1; nz++) {
                        BlockPos candidate = feetPosition.offset(nx, ny, nz);
                        if (!level.hasChunkAt(candidate)) {
                            neighborhoodComplete = false;
                            continue;
                        }
                        BlockState state = level.getBlockState(candidate);
                        if (hazardType(state) != null) nearbyHazards++;
                        if (!state.getFluidState().isEmpty()) nearbyFluids++;
                    }
                }
            }
            boolean supportSafe = dropComplete && dropDepth == 0 &&
                !support.getCollisionShape(level, supportPosition).isEmpty() &&
                support.getFluidState().isEmpty() && hazardType(support) == null;
            boolean evidenceComplete = loaded && neighborhoodComplete && dropComplete;
            int relativeOffset = Math.floorMod(index - facing, 4);
            String relative = switch (relativeOffset) {
                case 0 -> "forward";
                case 1 -> "right";
                case 2 -> "back";
                default -> "left";
            };
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardinal_direction", cardinals[index]);
            row.put("relative_direction", relative);
            row.put("target_feet_position", position(feetPosition));
            row.put("support_position", position(supportPosition));
            row.put("support_block", dropComplete ? blockId(support) : "helix:unobserved");
            row.put("evidence_complete", evidenceComplete);
            row.put("feet_clear", feetClear);
            row.put("head_clear", headClear);
            row.put("drop_depth_blocks", dropDepth);
            row.put("drop_scan_complete", dropComplete);
            row.put("nearby_hazard_count", nearbyHazards);
            row.put("nearby_fluid_count", nearbyFluids);
            row.put(
                "safe_candidate",
                evidenceComplete && feetClear && headClear && supportSafe &&
                nearbyHazards == 0 && nearbyFluids == 0
            );
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static Map<String, Object> inventory(
        ServerPlayer player,
        HelixSensorConfig config
    ) {
        List<Map<String, Object>> slots = new ArrayList<>();
        int size = Math.min(
            player.getInventory().getContainerSize(),
            config.snapshotOptions().maxInventoryStacks()
        );
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            slots.add(Map.of(
                "slot", slot,
                "item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())),
                "count", stack.getCount()
            ));
        }
        return Map.of("item_count", slots.size(), "slots", List.copyOf(slots));
    }

    private static Map<String, Object> navigationFrontier(
        ServerLevel level,
        ServerPlayer player,
        int horizontalRadius,
        int verticalRadius
    ) {
        BlockPos actor = player.blockPosition();
        BoundedNavigationFrontier.Position origin = frontierPosition(actor);
        BoundedNavigationFrontier.Result result =
            BoundedNavigationFrontier.analyze(
                origin,
                horizontalRadius,
                Math.min(verticalRadius, 6),
                8,
                position -> frontierCell(level, position)
            );
        List<Map<String, Object>> routes = result.rankedFrontiers().stream()
            .map(FabricPerceptionSnapshot::frontierRoute)
            .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("frontier_schema", "helix.minecraft_navigation_frontier.v1");
        output.put("planner", "casimirbot_native_bounded_dijkstra");
        output.put(
            "movement_model",
            List.of("walk", "diagonal", "ascend", "descend")
        );
        output.put("origin", position(actor));
        output.put("horizontal_radius", result.horizontalRadius());
        output.put("vertical_radius", result.verticalRadius());
        output.put(
            "reachable_foothold_count",
            result.reachableFootholdCount()
        );
        output.put("evidence_complete", result.evidenceComplete());
        output.put(
            "coverage_boundary_reached",
            result.coverageBoundaryReached()
        );
        output.put(
            "route_step_limit_reached",
            result.routeStepLimitReached()
        );
        output.put("ranked_frontiers", routes);
        output.put(
            "selection_authority",
            "runtime_codex"
        );
        return output;
    }

    private static Map<String, Object> frontierRoute(
        BoundedNavigationFrontier.Route route
    ) {
        List<Map<String, Object>> steps = route.steps().stream()
            .map(step -> Map.<String, Object>of(
                "from", frontierPosition(step.from()),
                "to", frontierPosition(step.to()),
                "movement", step.kind().name().toLowerCase(),
                "cost", step.cost()
            ))
            .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("destination", frontierPosition(route.destination()));
        output.put("steps", steps);
        output.put("cost", route.cost());
        output.put("displacement_blocks", round(route.displacement()));
        output.put("vertical_gain_blocks", route.verticalGain());
        output.put("coverage_boundary", route.coverageBoundary());
        return output;
    }

    private static Map<String, Object> frontierPosition(
        BoundedNavigationFrontier.Position position
    ) {
        return Map.of(
            "x", position.x(),
            "y", position.y(),
            "z", position.z()
        );
    }

    private static BoundedNavigationFrontier.Position frontierPosition(
        BlockPos position
    ) {
        return new BoundedNavigationFrontier.Position(
            position.getX(),
            position.getY(),
            position.getZ()
        );
    }

    private static BoundedNavigationFrontier.CellKind frontierCell(
        ServerLevel level,
        BoundedNavigationFrontier.Position position
    ) {
        BlockPos blockPosition = new BlockPos(
            position.x(),
            position.y(),
            position.z()
        );
        if (!level.hasChunkAt(blockPosition)) {
            return BoundedNavigationFrontier.CellKind.UNKNOWN;
        }
        BlockState state = level.getBlockState(blockPosition);
        if (hazardType(state) != null) {
            return BoundedNavigationFrontier.CellKind.HAZARD;
        }
        if (!state.getFluidState().isEmpty()) {
            return BoundedNavigationFrontier.CellKind.FLUID;
        }
        if (clearance(state)) {
            return BoundedNavigationFrontier.CellKind.CLEAR;
        }
        if (!state.getCollisionShape(level, blockPosition).isEmpty()) {
            return BoundedNavigationFrontier.CellKind.SOLID;
        }
        return BoundedNavigationFrontier.CellKind.BLOCKED;
    }

    private static Coverage coverage(
        ServerLevel level,
        BlockPos center,
        int horizontalRadius,
        int verticalRadius
    ) {
        int unknown = 0;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if (!level.hasChunkAt(center.offset(dx, dy, dz))) unknown++;
                }
            }
        }
        return new Coverage(unknown);
    }

    private static boolean clearance(BlockState state) {
        return (state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty())) &&
            hazardType(state) == null;
    }

    private static String classification(Entity entity) {
        if (entity instanceof Monster) return "hostile";
        if (entity instanceof ServerPlayer) return "player";
        if (entity instanceof Projectile) return "projectile";
        if (entity instanceof ItemEntity) return "item";
        if (entity instanceof LivingEntity) return "passive";
        return "other";
    }

    static String hazardType(BlockState state) {
        if (state.is(Blocks.LAVA)) return "lava";
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return "fire";
        if (state.is(Blocks.MAGMA_BLOCK)) return "magma";
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return "campfire";
        if (state.is(Blocks.CACTUS)) return "cactus";
        if (state.is(Blocks.POWDER_SNOW)) return "powder_snow";
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry_bush";
        if (state.is(Blocks.WITHER_ROSE)) return "wither_rose";
        if (state.is(Blocks.POINTED_DRIPSTONE)) return "pointed_dripstone";
        return null;
    }

    private static double bearing(float actorYaw, double dx, double dz) {
        double worldYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = worldYaw - actorYaw;
        while (relative > 180.0d) relative -= 360.0d;
        while (relative < -180.0d) relative += 360.0d;
        return round(relative);
    }

    private static Map<String, Object> position(Vec3 value) {
        return Map.of("x", round(value.x), "y", round(value.y), "z", round(value.z));
    }

    private static Map<String, Object> position(
        ProjectileThreatForecaster.Vector value
    ) {
        return Map.of("x", round(value.x()), "y", round(value.y()), "z", round(value.z()));
    }

    private static ProjectileThreatForecaster.Vector vector(Vec3 value) {
        return new ProjectileThreatForecaster.Vector(value.x, value.y, value.z);
    }

    private static Vec3 vector(ProjectileThreatForecaster.Vector value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private static String blockId(BlockState state) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record EntityCollection(List<Map<String, Object>> entities, boolean complete) {}
    private record ProjectileCollection(
        List<Map<String, Object>> projectiles,
        boolean complete
    ) {}
    private record TrajectoryCoverage(boolean complete, Integer occlusionTick) {}
    private record HazardCollection(List<Map<String, Object>> hazards, boolean complete) {}
    private record Coverage(int unknownCells) {}
}
