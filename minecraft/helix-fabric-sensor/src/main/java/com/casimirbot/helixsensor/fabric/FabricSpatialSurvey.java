package com.casimirbot.helixsensor.fabric;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds a bounded, compact block survey for model-side spatial reasoning.
 * The survey is evidence only: it never chooses a build plan or mutates the
 * world.
 */
final class FabricSpatialSurvey {
    static final int DEFAULT_HORIZONTAL_RADIUS = 7;
    static final int DEFAULT_VERTICAL_RADIUS = 6;
    static final int MAX_HORIZONTAL_RADIUS = 7;
    // A 15x15x33 survey remains below the 10,000-cell broker ceiling while
    // allowing an elevated actor to observe an exact bounded landing surface.
    static final int MAX_VERTICAL_RADIUS = 16;
    static final int MAX_DETAILS_JSON_BYTES = 33_500;

    private static final int MAX_RUNS_PER_COLUMN = 8;
    private static final int MAX_ANCHORS = 32;
    private static final int MAX_FIREPLACE_CANDIDATES = 16;
    private static final int MAX_BUILD_LINE_CANDIDATES = 16;
    private static final int MAX_WALK_STEP_CANDIDATES = 4;
    private static final int MAX_TARGET_MISMATCH_SAMPLES = 32;
    private static final int MAX_TARGET_VERIFICATION_CELLS = 4_096;
    private static final int MIN_BUILD_LINE_LENGTH = 3;
    private static final int MIN_BUILD_CLEAR_HEIGHT = 3;
    private static final int MIN_ACTOR_CLEARANCE = 2;
    private static final int MIN_ANCHOR_CLEARANCE = 3;
    private static final int MIN_PRIMARY_COLUMNS = 9;
    private static final String COLUMN_ENCODING =
        "relative_xz_relative_y_palette_flags_v1";
    private static final Gson JSON = new Gson();

    private static final Set<String> PURPOSES = Set.of(
        "general",
        "structure_planning",
        "build_planning",
        "structure_verification",
        "fire_safety",
        "landing_safety",
        "movement_safety"
    );
    private static final Set<String> HEARTH_BASES = Set.of(
        "minecraft:netherrack",
        "minecraft:soul_sand",
        "minecraft:soul_soil",
        "minecraft:magma_block"
    );

    record Cell(String block, List<String> flags) {
        boolean has(String flag) {
            return flags.contains(flag);
        }
    }

    private record ColumnOffset(int dx, int dz) {}

    record BuildSurface(
        BlockPos basePosition,
        int clearHeight,
        String groundBlock,
        int nearestAnchorDistance
    ) {}

    private FabricSpatialSurvey() {}

    static Map<String, Object> inspect(
        ServerLevel level,
        BlockPos requestedCenter,
        int requestedHorizontalRadius,
        int requestedVerticalRadius,
        String requestedPurpose,
        Integer requestedLength,
        Integer requestedHeight,
        String requestedOrientation,
        String requestedRelativeSide,
        float requestedActorYawDegrees,
        BlockPos requestedVerificationFrom,
        BlockPos requestedVerificationTo,
        String requestedExpectedBlock
    ) {
        int horizontalRadius = clamp(
            requestedHorizontalRadius,
            1,
            MAX_HORIZONTAL_RADIUS
        );
        int verticalRadius = clamp(
            requestedVerticalRadius,
            1,
            MAX_VERTICAL_RADIUS
        );
        String purpose = PURPOSES.contains(requestedPurpose)
            ? requestedPurpose
            : "general";
        Integer exactLength = requestedLength != null &&
                requestedLength >= MIN_BUILD_LINE_LENGTH &&
                requestedLength <= 15
            ? requestedLength
            : null;
        int minimumBuildHeight = requestedHeight != null &&
                requestedHeight >= MIN_BUILD_CLEAR_HEIGHT &&
                requestedHeight <= MAX_VERTICAL_RADIUS
            ? requestedHeight
            : MIN_BUILD_CLEAR_HEIGHT;
        String exactOrientation = (
                "north_south".equals(requestedOrientation) ||
                "east_west".equals(requestedOrientation)
            )
            ? requestedOrientation
            : null;
        String exactRelativeSide = (
                "north".equals(requestedRelativeSide) ||
                "south".equals(requestedRelativeSide) ||
                "east".equals(requestedRelativeSide) ||
                "west".equals(requestedRelativeSide)
            )
            ? requestedRelativeSide
            : null;
        BlockPos center = requestedCenter.immutable();
        BlockPos minimum = center.offset(
            -horizontalRadius,
            -verticalRadius,
            -horizontalRadius
        );
        BlockPos maximum = center.offset(
            horizontalRadius,
            verticalRadius,
            horizontalRadius
        );

        Map<BlockPos, Cell> cells = new HashMap<>();
        Map<String, Integer> paletteCounts = new TreeMap<>();
        List<Map<String, Object>> anchors = new ArrayList<>();
        List<BlockPos> anchorPositions = new ArrayList<>();
        int anchorCount = 0;
        for (int x = minimum.getX(); x <= maximum.getX(); x++) {
            for (int z = minimum.getZ(); z <= maximum.getZ(); z++) {
                for (int y = minimum.getY(); y <= maximum.getY(); y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    Cell cell = cell(level.getBlockState(position));
                    cells.put(position, cell);
                    paletteCounts.merge(cell.block(), 1, Integer::sum);
                    String anchorKind = anchorKind(cell.block());
                    if (anchorKind != null) {
                        anchorCount++;
                        anchorPositions.add(position.immutable());
                        if (anchors.size() < MAX_ANCHORS) {
                            anchors.add(
                                Map.of(
                                    "kind",
                                    anchorKind,
                                    "block",
                                    cell.block(),
                                    "position",
                                    position(position)
                                )
                            );
                        }
                    }
                }
            }
        }

        List<Map.Entry<String, Integer>> orderedPaletteEntries = paletteCounts
            .entrySet()
            .stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey())
            )
            .toList();
        List<Map<String, Object>> palette = new ArrayList<>();
        orderedPaletteEntries
            .stream()
            .limit(127)
            .map(entry ->
                Map.<String, Object>of(
                    "block",
                    entry.getKey(),
                    "count",
                    entry.getValue()
                )
            )
            .forEach(palette::add);
        int omittedPaletteBlockTypes = Math.max(
            0,
            orderedPaletteEntries.size() - palette.size()
        );
        int unrepresentedBlockCount = orderedPaletteEntries
            .stream()
            .skip(palette.size())
            .mapToInt(Map.Entry::getValue)
            .sum();
        if (omittedPaletteBlockTypes > 0) {
            palette.add(
                Map.of(
                    "block",
                    "helix:unrepresented",
                    "count",
                    unrepresentedBlockCount
                )
            );
        }
        Map<String, Integer> paletteIndex = new HashMap<>();
        for (int index = 0; index < palette.size(); index++) {
            paletteIndex.put(String.valueOf(palette.get(index).get("block")), index);
        }
        int unrepresentedPaletteIndex = paletteIndex.getOrDefault(
            "helix:unrepresented",
            0
        );

        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                offsets.add(new ColumnOffset(dx, dz));
            }
        }
        offsets.sort(
            Comparator.comparingInt((ColumnOffset offset) ->
                Math.max(Math.abs(offset.dx()), Math.abs(offset.dz()))
            )
                .thenComparingInt(offset ->
                    Math.abs(offset.dx()) + Math.abs(offset.dz())
                )
                .thenComparingInt(ColumnOffset::dx)
                .thenComparingInt(ColumnOffset::dz)
        );

        List<Map<String, Object>> columns = new ArrayList<>();
        int omittedRunCount = 0;
        for (ColumnOffset offset : offsets) {
            int x = center.getX() + offset.dx();
            int z = center.getZ() + offset.dz();
            List<Map<String, Object>> runs = new ArrayList<>();
            int runStart = minimum.getY();
            Cell runCell = cells.get(new BlockPos(x, runStart, z));
            for (int y = minimum.getY() + 1; y <= maximum.getY(); y++) {
                Cell next = cells.get(new BlockPos(x, y, z));
                if (!next.equals(runCell)) {
                    runs.add(
                        compactRun(
                            center,
                            runStart,
                            y - 1,
                            runCell,
                            paletteIndex,
                            unrepresentedPaletteIndex
                        )
                    );
                    runStart = y;
                    runCell = next;
                }
            }
            runs.add(
                compactRun(
                    center,
                    runStart,
                    maximum.getY(),
                    runCell,
                    paletteIndex,
                    unrepresentedPaletteIndex
                )
            );
            if (runs.size() > MAX_RUNS_PER_COLUMN) {
                int edgeCount = MAX_RUNS_PER_COLUMN / 2;
                List<Map<String, Object>> bounded = new ArrayList<>(MAX_RUNS_PER_COLUMN);
                bounded.addAll(runs.subList(0, edgeCount));
                bounded.addAll(runs.subList(runs.size() - edgeCount, runs.size()));
                omittedRunCount += runs.size() - bounded.size();
                runs = bounded;
            }
            columns.add(
                Map.of(
                    "offset",
                    List.of(offset.dx(), offset.dz()),
                    "runs",
                    List.copyOf(runs)
                )
            );
        }

        List<Map.Entry<BlockPos, Cell>> fireplaceBases = cells
            .entrySet()
            .stream()
            .filter(entry -> HEARTH_BASES.contains(entry.getValue().block()))
            .sorted(
                Comparator.comparingInt((Map.Entry<BlockPos, Cell> entry) ->
                    entry.getKey().distManhattan(center)
                ).thenComparingInt(entry -> entry.getKey().getY())
            )
            .toList();
        int fireplaceCandidateCount = fireplaceBases.size();
        List<Map<String, Object>> fireplaceCandidates = new ArrayList<>(
            fireplaceBases
                .stream()
                .limit(MAX_FIREPLACE_CANDIDATES)
            .map(entry -> fireplaceCandidate(level, entry.getKey(), entry.getValue()))
                .toList()
        );
        List<BuildSurface> buildSurfaces = purpose.equals("build_planning") ||
            purpose.equals("structure_planning")
            ? buildSurfaces(
                cells,
                anchorPositions,
                center,
                minimum,
                maximum,
                minimumBuildHeight
            )
            : List.of();
        List<Map<String, Object>> allBuildLineCandidates =
            enumerateBuildLineCandidates(
                buildSurfaces,
                center,
                exactLength,
                exactOrientation,
                exactRelativeSide
            );
        List<Map<String, Object>> buildLineCandidates = new ArrayList<>(
            allBuildLineCandidates
                .stream()
                .limit(MAX_BUILD_LINE_CANDIDATES)
                .toList()
        );
        List<Map<String, Object>> walkStepCandidates =
            purpose.equals("movement_safety")
                ? walkStepCandidates(
                    cells,
                    center,
                    requestedActorYawDegrees
                )
                : List.of();
        Map<String, Object> targetGeometryVerification =
            purpose.equals("structure_verification")
                ? verifyTargetGeometry(
                    cells,
                    minimum,
                    maximum,
                    requestedVerificationFrom,
                    requestedVerificationTo,
                    requestedExpectedBlock
                )
                : null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", purpose);
        details.put("center", position(center));
        details.put("horizontal_radius", horizontalRadius);
        details.put("vertical_radius", verticalRadius);
        if (exactLength != null) details.put("requested_length", exactLength);
        if (requestedHeight != null) {
            details.put("requested_height", minimumBuildHeight);
        }
        if (exactOrientation != null) {
            details.put("requested_orientation", exactOrientation);
        }
        if (exactRelativeSide != null) {
            details.put("requested_relative_side", exactRelativeSide);
        }
        details.put("sample_count", cells.size());
        details.put(
            "bounds",
            Map.of("min", position(minimum), "max", position(maximum))
        );
        details.put("palette", palette);
        details.put("palette_complete", omittedPaletteBlockTypes == 0);
        details.put("omitted_palette_block_types", omittedPaletteBlockTypes);
        details.put("column_encoding", COLUMN_ENCODING);
        details.put("columns", columns);
        details.put("columns_complete", omittedRunCount == 0);
        details.put("retained_column_count", columns.size());
        details.put("omitted_column_count", 0);
        details.put("omitted_run_count", omittedRunCount);
        details.put("anchors", anchors);
        details.put("anchors_complete", anchorCount == anchors.size());
        details.put("retained_anchor_count", anchors.size());
        details.put("omitted_anchor_count", anchorCount - anchors.size());
        details.put("fireplace_candidates", fireplaceCandidates);
        details.put(
            "fireplace_candidates_complete",
            fireplaceCandidateCount == fireplaceCandidates.size()
        );
        details.put(
            "retained_fireplace_candidate_count",
            fireplaceCandidates.size()
        );
        details.put(
            "omitted_fireplace_candidate_count",
            fireplaceCandidateCount - fireplaceCandidates.size()
        );
        details.put("build_line_candidates", buildLineCandidates);
        details.put(
            "build_line_candidates_complete",
            allBuildLineCandidates.size() == buildLineCandidates.size()
        );
        details.put(
            "retained_build_line_candidate_count",
            buildLineCandidates.size()
        );
        details.put(
            "omitted_build_line_candidate_count",
            allBuildLineCandidates.size() - buildLineCandidates.size()
        );
        details.put("walk_step_candidates", walkStepCandidates);
        details.put("walk_step_candidates_complete", true);
        details.put(
            "retained_walk_step_candidate_count",
            walkStepCandidates.size()
        );
        details.put("omitted_walk_step_candidate_count", 0);
        if (targetGeometryVerification != null) {
            details.put(
                "target_geometry_verification",
                targetGeometryVerification
            );
        }
        int fullColumnCount = columns.size();
        enforceWireSize(
            details,
            columns,
            fullColumnCount,
            anchors,
            anchorCount,
            fireplaceCandidates,
            fireplaceCandidateCount
        );
        return details;
    }

    static Map<String, Object> verifyTargetGeometry(
        Map<BlockPos, Cell> cells,
        BlockPos surveyMinimum,
        BlockPos surveyMaximum,
        BlockPos requestedFrom,
        BlockPos requestedTo,
        String requestedExpectedBlock
    ) {
        String expectedBlock = canonicalExpectedBlock(requestedExpectedBlock);
        if (
            requestedFrom == null ||
            requestedTo == null ||
            expectedBlock == null
        ) {
            return null;
        }
        BlockPos minimum = new BlockPos(
            Math.min(requestedFrom.getX(), requestedTo.getX()),
            Math.min(requestedFrom.getY(), requestedTo.getY()),
            Math.min(requestedFrom.getZ(), requestedTo.getZ())
        );
        BlockPos maximum = new BlockPos(
            Math.max(requestedFrom.getX(), requestedTo.getX()),
            Math.max(requestedFrom.getY(), requestedTo.getY()),
            Math.max(requestedFrom.getZ(), requestedTo.getZ())
        );
        long totalCellsLong =
            (long) (maximum.getX() - minimum.getX() + 1) *
            (long) (maximum.getY() - minimum.getY() + 1) *
            (long) (maximum.getZ() - minimum.getZ() + 1);
        if (
            totalCellsLong < 1 ||
            totalCellsLong > MAX_TARGET_VERIFICATION_CELLS
        ) {
            return null;
        }
        int totalCells = (int) totalCellsLong;
        boolean withinSurveyBounds =
            minimum.getX() >= surveyMinimum.getX() &&
            minimum.getY() >= surveyMinimum.getY() &&
            minimum.getZ() >= surveyMinimum.getZ() &&
            maximum.getX() <= surveyMaximum.getX() &&
            maximum.getY() <= surveyMaximum.getY() &&
            maximum.getZ() <= surveyMaximum.getZ();
        int sampledCells = 0;
        int matchingCells = 0;
        int mismatchedCells = 0;
        List<Map<String, Object>> mismatchSamples = new ArrayList<>();
        for (int x = minimum.getX(); x <= maximum.getX(); x++) {
            for (int y = minimum.getY(); y <= maximum.getY(); y++) {
                for (int z = minimum.getZ(); z <= maximum.getZ(); z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    Cell observed = cells.get(position);
                    if (observed == null) continue;
                    sampledCells++;
                    if (expectedBlock.equals(observed.block())) {
                        matchingCells++;
                        continue;
                    }
                    mismatchedCells++;
                    if (
                        mismatchSamples.size() < MAX_TARGET_MISMATCH_SAMPLES
                    ) {
                        mismatchSamples.add(
                            Map.of(
                                "position",
                                position(position),
                                "observed_block",
                                observed.block()
                            )
                        );
                    }
                }
            }
        }
        int unobservedCells = totalCells - sampledCells;
        boolean complete = withinSurveyBounds && unobservedCells == 0;
        boolean allMatch =
            complete && mismatchedCells == 0 && matchingCells == totalCells;
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("from", position(requestedFrom));
        verification.put("to", position(requestedTo));
        verification.put("expected_block", expectedBlock);
        verification.put("total_cells", totalCells);
        verification.put("sampled_cells", sampledCells);
        verification.put("matching_cells", matchingCells);
        verification.put("mismatched_cells", mismatchedCells);
        verification.put("unobserved_cells", unobservedCells);
        verification.put("mismatch_samples", List.copyOf(mismatchSamples));
        verification.put("within_survey_bounds", withinSurveyBounds);
        verification.put("complete", complete);
        verification.put("all_match", allMatch);
        return verification;
    }

    private static String canonicalExpectedBlock(String requestedBlock) {
        if (requestedBlock == null) return null;
        String block = requestedBlock.trim().toLowerCase();
        int stateStart = block.indexOf('[');
        if (stateStart >= 0) block = block.substring(0, stateStart);
        int nbtStart = block.indexOf('{');
        if (nbtStart >= 0) block = block.substring(0, nbtStart);
        if (!block.contains(":")) block = "minecraft:" + block;
        return block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? block : null;
    }

    private static List<BuildSurface> buildSurfaces(
        Map<BlockPos, Cell> cells,
        List<BlockPos> anchorPositions,
        BlockPos center,
        BlockPos minimum,
        BlockPos maximum,
        int minimumBuildHeight
    ) {
        List<BuildSurface> surfaces = new ArrayList<>();
        int highestGroundY = Math.min(center.getY(), maximum.getY() - 1);
        for (int x = minimum.getX(); x <= maximum.getX(); x++) {
            for (int z = minimum.getZ(); z <= maximum.getZ(); z++) {
                if (
                    Math.max(
                        Math.abs(x - center.getX()),
                        Math.abs(z - center.getZ())
                    ) < MIN_ACTOR_CLEARANCE
                ) {
                    continue;
                }
                for (int groundY = highestGroundY; groundY >= minimum.getY(); groundY--) {
                    BlockPos groundPosition = new BlockPos(x, groundY, z);
                    Cell ground = cells.get(groundPosition);
                    if (!isSafeBuildGround(ground)) {
                        continue;
                    }
                    int clearHeight = 0;
                    for (int y = groundY + 1; y <= maximum.getY(); y++) {
                        Cell candidate = cells.get(new BlockPos(x, y, z));
                        if (!isStrictAirBuildCell(candidate)) {
                            break;
                        }
                        clearHeight++;
                    }
                    if (clearHeight < minimumBuildHeight) continue;
                    int nearestAnchorDistance = anchorPositions
                        .stream()
                        .mapToInt(anchor -> anchor.distManhattan(groundPosition))
                        .min()
                        .orElse(1_000_000);
                    if (nearestAnchorDistance < MIN_ANCHOR_CLEARANCE) continue;
                    surfaces.add(
                        new BuildSurface(
                            groundPosition.above().immutable(),
                            clearHeight,
                            ground.block(),
                            nearestAnchorDistance
                        )
                    );
                    break;
                }
            }
        }
        return List.copyOf(surfaces);
    }

    static boolean isStrictAirBuildCell(Cell candidate) {
        return candidate != null &&
            candidate.has("air") &&
            !candidate.has("fluid") &&
            !candidate.has("flammable") &&
            !candidate.has("hazard") &&
            !candidate.has("block_entity");
    }

    static boolean isSafeBuildGround(Cell ground) {
        return ground != null &&
            ground.has("solid") &&
            !ground.has("fluid") &&
            !ground.has("hazard") &&
            !ground.has("flammable") &&
            !ground.has("block_entity") &&
            !ground.block().endsWith(":dirt_path");
    }

    static List<Map<String, Object>> enumerateBuildLineCandidates(
        List<BuildSurface> surfaces,
        BlockPos center
    ) {
        return enumerateBuildLineCandidates(
            surfaces,
            center,
            null,
            null,
            null
        );
    }

    static List<Map<String, Object>> enumerateBuildLineCandidates(
        List<BuildSurface> surfaces,
        BlockPos center,
        Integer requestedLength,
        String requestedOrientation,
        String requestedRelativeSide
    ) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (!"east_west".equals(requestedOrientation)) {
            collectBuildLineCandidates(
                surfaces,
                center,
                true,
                requestedLength,
                requestedRelativeSide,
                candidates
            );
        }
        if (!"north_south".equals(requestedOrientation)) {
            collectBuildLineCandidates(
                surfaces,
                center,
                false,
                requestedLength,
                requestedRelativeSide,
                candidates
            );
        }
        candidates.sort(
            Comparator
                .comparingInt((Map<String, Object> candidate) ->
                    ((Number) candidate.get("minimum_actor_distance")).intValue()
                )
                .thenComparing(
                    Comparator.comparingInt((Map<String, Object> candidate) ->
                        ((Number) candidate.get("nearest_anchor_distance")).intValue()
                    ).reversed()
                )
                .thenComparing(
                    Comparator.comparingInt((Map<String, Object> candidate) ->
                        ((Number) candidate.get("length")).intValue()
                    ).reversed()
                )
                .thenComparing(candidate -> String.valueOf(candidate.get("orientation")))
                .thenComparing(candidate -> String.valueOf(candidate.get("from")))
        );
        return List.copyOf(candidates);
    }

    private static void collectBuildLineCandidates(
        List<BuildSurface> surfaces,
        BlockPos center,
        boolean northSouth,
        Integer requestedLength,
        String requestedRelativeSide,
        List<Map<String, Object>> output
    ) {
        Map<String, List<BuildSurface>> grouped = new TreeMap<>();
        for (BuildSurface surface : surfaces) {
            BlockPos position = surface.basePosition();
            String key = northSouth
                ? position.getX() + ":" + position.getY()
                : position.getZ() + ":" + position.getY();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(surface);
        }
        for (List<BuildSurface> group : grouped.values()) {
            group.sort(
                northSouth
                    ? Comparator.comparingInt(surface -> surface.basePosition().getZ())
                    : Comparator.comparingInt(surface -> surface.basePosition().getX())
            );
            List<BuildSurface> run = new ArrayList<>();
            for (BuildSurface surface : group) {
                if (
                    !run.isEmpty() &&
                    varyingCoordinate(surface, northSouth) !=
                        varyingCoordinate(run.get(run.size() - 1), northSouth) + 1
                ) {
                    appendBuildLineCandidates(
                        run,
                        center,
                        northSouth,
                        requestedLength,
                        requestedRelativeSide,
                        output
                    );
                    run = new ArrayList<>();
                }
                run.add(surface);
            }
            appendBuildLineCandidates(
                run,
                center,
                northSouth,
                requestedLength,
                requestedRelativeSide,
                output
            );
        }
    }

    private static int varyingCoordinate(
        BuildSurface surface,
        boolean northSouth
    ) {
        return northSouth
            ? surface.basePosition().getZ()
            : surface.basePosition().getX();
    }

    private static void appendBuildLineCandidates(
        List<BuildSurface> run,
        BlockPos center,
        boolean northSouth,
        Integer requestedLength,
        String requestedRelativeSide,
        List<Map<String, Object>> output
    ) {
        int length = requestedLength == null
            ? run.size()
            : requestedLength;
        if (length < MIN_BUILD_LINE_LENGTH || run.size() < length) return;
        if (requestedLength == null) {
            appendBuildLineCandidate(
                run,
                center,
                northSouth,
                requestedRelativeSide,
                output
            );
            return;
        }
        for (int start = 0; start + length <= run.size(); start++) {
            appendBuildLineCandidate(
                run.subList(start, start + length),
                center,
                northSouth,
                requestedRelativeSide,
                output
            );
        }
    }

    private static void appendBuildLineCandidate(
        List<BuildSurface> run,
        BlockPos center,
        boolean northSouth,
        String requestedRelativeSide,
        List<Map<String, Object>> output
    ) {
        BuildSurface first = run.get(0);
        BuildSurface last = run.get(run.size() - 1);
        BlockPos from = first.basePosition();
        BlockPos to = last.basePosition();
        int minimumActorDistance = run
            .stream()
            .mapToInt(surface -> {
                BlockPos position = surface.basePosition();
                return Math.max(
                    Math.abs(position.getX() - center.getX()),
                    Math.abs(position.getZ() - center.getZ())
                );
            })
            .min()
            .orElse(0);
        int nearestAnchorDistance = run
            .stream()
            .mapToInt(BuildSurface::nearestAnchorDistance)
            .min()
            .orElse(1_000_000);
        int minimumClearHeight = run
            .stream()
            .mapToInt(BuildSurface::clearHeight)
            .min()
            .orElse(0);
        List<String> groundBlocks = run
            .stream()
            .map(BuildSurface::groundBlock)
            .distinct()
            .sorted()
            .toList();
        int midpointX = Math.floorDiv(from.getX() + to.getX(), 2);
        int midpointZ = Math.floorDiv(from.getZ() + to.getZ(), 2);
        String relativeSide = relativeSide(
            midpointX - center.getX(),
            midpointZ - center.getZ()
        );
        if (
            requestedRelativeSide != null &&
            !requestedRelativeSide.equals(relativeSide)
        ) {
            return;
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("orientation", northSouth ? "north_south" : "east_west");
        candidate.put("relative_side", relativeSide);
        candidate.put("from", position(from));
        candidate.put("to", position(to));
        candidate.put("length", run.size());
        candidate.put("minimum_clear_height", minimumClearHeight);
        candidate.put("minimum_actor_distance", minimumActorDistance);
        candidate.put("nearest_anchor_distance", nearestAnchorDistance);
        candidate.put("ground_blocks", groundBlocks);
        candidate.put("target_cells_replaceable", true);
        candidate.put("target_cells_air", true);
        candidate.put("ground_solid_nonhazardous", true);
        candidate.put("fluid_cells", 0);
        candidate.put("flammable_cells", 0);
        candidate.put("block_entity_cells", 0);
        candidate.put("safe_candidate", true);
        output.add(candidate);
    }

    private static String relativeSide(int dx, int dz) {
        if (dx == 0 && dz == 0) return "overlap";
        if (Math.abs(dx) >= Math.abs(dz)) return dx < 0 ? "west" : "east";
        return dz < 0 ? "north" : "south";
    }

    /**
     * Produces four conservative, actor-relative one-block movement
     * affordances.  These are evidence, not a movement decision: the runtime
     * model still chooses whether to act, while Helix can prove that the
     * chosen relative direction was actually represented by fresh geometry.
     */
    private static List<Map<String, Object>> walkStepCandidates(
        Map<BlockPos, Cell> cells,
        BlockPos center,
        float actorYawDegrees
    ) {
        String[] cardinalDirections = {"south", "west", "north", "east"};
        int[] deltaX = {0, -1, 0, 1};
        int[] deltaZ = {1, 0, -1, 0};
        int facingIndex = Math.floorMod(
            Math.round(actorYawDegrees / 90.0F),
            cardinalDirections.length
        );
        List<Map<String, Object>> candidates = new ArrayList<>(
            MAX_WALK_STEP_CANDIDATES
        );
        for (int index = 0; index < cardinalDirections.length; index++) {
            BlockPos targetFeet = center.offset(deltaX[index], 0, deltaZ[index]);
            BlockPos targetHead = targetFeet.above();
            BlockPos supportPosition = targetFeet.below();
            Cell feet = cells.get(targetFeet);
            Cell head = cells.get(targetHead);
            Cell support = cells.get(supportPosition);
            boolean evidenceComplete = feet != null && head != null && support != null;
            boolean feetClear = isStrictMovementClearance(feet);
            boolean headClear = isStrictMovementClearance(head);
            boolean supportSolidNonhazardous =
                support != null &&
                support.has("solid") &&
                !support.has("fluid") &&
                !support.has("hazard");
            int nearbyHazardCount = 0;
            int nearbyFluidCount = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Cell nearby = cells.get(targetFeet.offset(dx, dy, dz));
                        if (nearby == null) {
                            evidenceComplete = false;
                            continue;
                        }
                        if (nearby.has("hazard")) nearbyHazardCount++;
                        if (nearby.has("fluid")) nearbyFluidCount++;
                    }
                }
            }
            boolean safe =
                evidenceComplete &&
                feetClear &&
                headClear &&
                supportSolidNonhazardous &&
                nearbyHazardCount == 0 &&
                nearbyFluidCount == 0;
            int relativeOffset = Math.floorMod(index - facingIndex, 4);
            String relativeDirection = switch (relativeOffset) {
                case 0 -> "forward";
                case 1 -> "right";
                case 2 -> "back";
                default -> "left";
            };
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("cardinal_direction", cardinalDirections[index]);
            candidate.put("relative_direction", relativeDirection);
            candidate.put("target_feet_position", position(targetFeet));
            candidate.put("target_head_position", position(targetHead));
            candidate.put("support_position", position(supportPosition));
            candidate.put(
                "support_block",
                support == null ? "helix:unobserved" : support.block()
            );
            candidate.put("evidence_complete", evidenceComplete);
            candidate.put("feet_clear", feetClear);
            candidate.put("head_clear", headClear);
            candidate.put(
                "support_solid_nonhazardous",
                supportSolidNonhazardous
            );
            candidate.put("nearby_hazard_count", nearbyHazardCount);
            candidate.put("nearby_fluid_count", nearbyFluidCount);
            candidate.put("safe_candidate", safe);
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private static boolean isStrictMovementClearance(Cell cell) {
        if (cell == null) return false;
        return (
            cell.has("air") ||
            (
                cell.has("replaceable") &&
                !cell.has("solid") &&
                !cell.has("block_entity")
            )
        ) &&
            !cell.has("fluid") &&
            !cell.has("hazard");
    }

    static void enforceWireSize(
        Map<String, Object> details,
        List<Map<String, Object>> columns,
        int fullColumnCount,
        List<Map<String, Object>> anchors,
        int fullAnchorCount,
        List<Map<String, Object>> fireplaceCandidates,
        int fullFireplaceCandidateCount
    ) {
        int wireBytes = recordWireDetailsJsonBytes(details);
        while (wireBytes > MAX_DETAILS_JSON_BYTES && columns.size() > MIN_PRIMARY_COLUMNS) {
            columns.remove(columns.size() - 1);
            details.put("columns_complete", false);
            details.put("retained_column_count", columns.size());
            details.put("omitted_column_count", fullColumnCount - columns.size());
            wireBytes = recordWireDetailsJsonBytes(details);
        }
        while (wireBytes > MAX_DETAILS_JSON_BYTES && !anchors.isEmpty()) {
            anchors.remove(anchors.size() - 1);
            details.put("anchors_complete", false);
            details.put("retained_anchor_count", anchors.size());
            details.put("omitted_anchor_count", fullAnchorCount - anchors.size());
            wireBytes = recordWireDetailsJsonBytes(details);
        }
        while (
            wireBytes > MAX_DETAILS_JSON_BYTES &&
            !fireplaceCandidates.isEmpty()
        ) {
            fireplaceCandidates.remove(fireplaceCandidates.size() - 1);
            details.put("fireplace_candidates_complete", false);
            details.put(
                "retained_fireplace_candidate_count",
                fireplaceCandidates.size()
            );
            details.put(
                "omitted_fireplace_candidate_count",
                fullFireplaceCandidateCount - fireplaceCandidates.size()
            );
            wireBytes = recordWireDetailsJsonBytes(details);
        }
        while (wireBytes > MAX_DETAILS_JSON_BYTES && columns.size() > 1) {
            columns.remove(columns.size() - 1);
            details.put("columns_complete", false);
            details.put("retained_column_count", columns.size());
            details.put("omitted_column_count", fullColumnCount - columns.size());
            wireBytes = recordWireDetailsJsonBytes(details);
        }
        if (wireBytes > MAX_DETAILS_JSON_BYTES) {
            throw new IllegalStateException(
                "Spatial evidence cannot fit the bounded connector envelope."
            );
        }
    }

    static int recordWireDetailsJsonBytes(Map<String, Object> details) {
        int reported = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            details.put("wire_details_json_bytes", reported);
            int actual = jsonBytes(details);
            if (actual == reported) return actual;
            reported = actual;
        }
        throw new IllegalStateException(
            "Spatial evidence byte accounting did not converge."
        );
    }

    private static Map<String, Object> fireplaceCandidate(
        ServerLevel level,
        BlockPos base,
        Cell baseCell
    ) {
        BlockPos fire = base.above();
        Cell fireCell = cell(level.getBlockState(fire));
        int enclosure = 0;
        for (
            BlockPos position : List.of(
                fire.north(),
                fire.south(),
                fire.east(),
                fire.west(),
                fire.above()
            )
        ) {
            Cell candidate = cell(level.getBlockState(position));
            if (candidate.has("solid") && !candidate.has("flammable")) {
                enclosure++;
            }
        }
        int flammableWithinTwo = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (
                        cell(level.getBlockState(fire.offset(dx, dy, dz)))
                            .has("flammable")
                    ) {
                        flammableWithinTwo++;
                    }
                }
            }
        }
        boolean replaceable =
            fireCell.has("air") ||
            (
                fireCell.has("replaceable") &&
                !fireCell.has("fluid") &&
                !fireCell.has("block_entity")
            );
        boolean safe =
            replaceable && flammableWithinTwo == 0 && enclosure >= 3;
        return Map.of(
            "base_position",
            position(base),
            "fire_position",
            position(fire),
            "base_block",
            baseCell.block(),
            "flammable_within_two",
            flammableWithinTwo,
            "solid_nonflammable_enclosure",
            enclosure,
            "replaceable_fire_cell",
            replaceable,
            "safe_candidate",
            safe
        );
    }

    private static Cell cell(BlockState state) {
        List<String> flags = new ArrayList<>(7);
        if (state.isAir()) flags.add("air");
        if (!state.getFluidState().isEmpty()) flags.add("fluid");
        if (state.blocksMotion()) flags.add("solid");
        if (state.ignitedByLava()) flags.add("flammable");
        if (state.canBeReplaced()) flags.add("replaceable");
        if (FabricProbeExecutor.hazardType(state) != null) flags.add("hazard");
        if (state.hasBlockEntity()) flags.add("block_entity");
        return new Cell(FabricProbeExecutor.blockId(state), List.copyOf(flags));
    }

    private static Map<String, Object> compactRun(
        BlockPos center,
        int start,
        int end,
        Cell cell,
        Map<String, Integer> paletteIndex,
        int unrepresentedPaletteIndex
    ) {
        return Map.of(
            "y",
            List.of(start - center.getY(), end - center.getY()),
            "p",
            paletteIndex.getOrDefault(cell.block(), unrepresentedPaletteIndex),
            "f",
            flagsMask(cell.flags())
        );
    }

    private static int flagsMask(List<String> flags) {
        int mask = 0;
        if (flags.contains("air")) mask |= 1;
        if (flags.contains("fluid")) mask |= 2;
        if (flags.contains("solid")) mask |= 4;
        if (flags.contains("flammable")) mask |= 8;
        if (flags.contains("replaceable")) mask |= 16;
        if (flags.contains("hazard")) mask |= 32;
        if (flags.contains("block_entity")) mask |= 64;
        return mask;
    }

    private static int jsonBytes(Map<String, Object> value) {
        return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, Object> position(BlockPos position) {
        return Map.of(
            "x",
            position.getX(),
            "y",
            position.getY(),
            "z",
            position.getZ()
        );
    }

    private static String anchorKind(String block) {
        if (block.endsWith("_door") && !block.endsWith("_trapdoor")) {
            return "door";
        }
        if (block.endsWith("_bed")) return "bed";
        if (
            block.contains("chest") ||
            block.endsWith(":barrel") ||
            block.contains("shulker_box")
        ) {
            return "container";
        }
        if (
            block.endsWith(":crafting_table") ||
            block.endsWith(":furnace") ||
            block.endsWith(":blast_furnace") ||
            block.endsWith(":smoker") ||
            block.endsWith(":stonecutter") ||
            block.endsWith(":smithing_table") ||
            block.endsWith(":cartography_table") ||
            block.endsWith(":fletching_table") ||
            block.endsWith(":loom") ||
            block.endsWith(":grindstone") ||
            block.endsWith(":brewing_stand") ||
            block.contains("anvil")
        ) {
            return "workstation";
        }
        if (block.endsWith("_portal") || block.endsWith(":end_gateway")) {
            return "portal";
        }
        return HEARTH_BASES.contains(block) ? "hearth_base" : null;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
