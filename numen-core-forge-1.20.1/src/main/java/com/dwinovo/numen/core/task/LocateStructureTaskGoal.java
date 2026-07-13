package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.CompanionTask;

import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocateStructureTaskGoal implements CompanionTask {

    private static final int SEARCH_RADIUS_RINGS = 100;

    private static final class Job {
        final StructurePlacement placement;
        final List<Structure> structures = new ArrayList<>(1);
        RandomSpreadStructurePlacement spread;
        long seed;
        int centerRegX, centerRegZ, maxRing, ring, perimIdx;
        List<ChunkPos> ringPositions;

        Job(StructurePlacement placement) {
            this.placement = placement;
        }

        ChunkPos next() {
            if (spread == null) return null;
            while (ring <= maxRing) {
                if (perimIdx >= RingSpiral.perimeter(ring)) {
                    ring++;
                    perimIdx = 0;
                    continue;
                }
                int[] d = RingSpiral.offset(ring, perimIdx++);
                return spread.getPotentialStructureChunk(seed,
                        (centerRegX + d[0]) * spread.spacing(),
                        (centerRegZ + d[1]) * spread.spacing());
            }
            return null;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private int jobIndex;
    private ChunkPos pendingCandidate;
    private BlockPos best;
    private double bestDistSqr = Double.MAX_VALUE;
    private String failReason = "not on a server level";

    private final NumenPlayer player;
    private final LocateStructureTaskRecord r;

    public LocateStructureTaskGoal(NumenPlayer player, LocateStructureTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        jobs.clear();
        jobIndex = 0;
        pendingCandidate = null;
        best = null;
        bestDistSqr = Double.MAX_VALUE;

        if (!(player.level instanceof ServerLevel sl)) {
            failReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return;
        }
        List<Holder<Structure>> holders = resolveStructures(sl, r.structure.trim());
        if (holders == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        if (holders.isEmpty()) {
            r.setState(TaskState.SUCCESS);
            return;
        }

        ChunkGeneratorStructureState state = sl.getChunkSource().getGeneratorState();
        ChunkPos here = player.chunkPosition();
        Map<StructurePlacement, Job> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(placement, Job::new)
                        .structures.add(holder.value());
            }
        }
        for (Job job : byPlacement.values()) {
            if (job.placement instanceof RandomSpreadStructurePlacement spread) {
                job.spread = spread;
                job.seed = state.getLevelSeed();
                job.centerRegX = Math.floorDiv(here.x, spread.spacing());
                job.centerRegZ = Math.floorDiv(here.z, spread.spacing());
                job.maxRing = SEARCH_RADIUS_RINGS;
                jobs.add(job);
            } else if (job.placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions != null) {
                    for (ChunkPos cp : positions) {
                        consider(job.placement.getLocatePos(cp));
                    }
                }
            }
        }
        if (jobs.isEmpty()) {
            r.setState(TaskState.SUCCESS);
        }
    }

    private List<Holder<Structure>> resolveStructures(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> out = new ArrayList<>();
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid structure tag: " + arg;
                return null;
            }
            var set = registry.getTag(TagKey.create(Registries.STRUCTURE, tagId));
            if (set.isEmpty()) {
                failReason = isBiomeTag(sl, tagId)
                        ? arg + " is a BIOME tag, not a structure tag — call "
                                + "locate_biome(biome=\"" + arg + "\") instead"
                        : "unknown structure tag: " + arg + " — try #minecraft:village "
                                + "or an id like minecraft:fortress";
                return null;
            }
            set.get().forEach(out::add);
            return out;
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        Optional<? extends Holder<Structure>> holder = id == null ? Optional.empty()
                : registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            if (id != null && isBiomeId(sl, id)) {
                failReason = arg + " is a BIOME, not a structure — call "
                        + "locate_biome(biome=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.holders().map(ref -> ref.key().location()), arg);
            failReason = "unknown structure: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a structure id like minecraft:fortress / "
                                    + "minecraft:stronghold, or a tag like #minecraft:village; "
                                    + "load_skill(world_atlas) lists every id");
            return null;
        }
        out.add(holder.get());
        return out;
    }

    private static boolean isBiomeId(ServerLevel sl, ResourceLocation id) {
        return sl.registryAccess().registryOrThrow(Registries.BIOME)
                .containsKey(ResourceKey.create(Registries.BIOME, id));
    }

    private static boolean isBiomeTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().registryOrThrow(Registries.BIOME)
                .getTag(TagKey.create(Registries.BIOME, tagId)).isPresent();
    }

    @Override
    public TaskState tick() {
        if (!(player.level instanceof ServerLevel sl)) {
            failReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return r.getState();
        }
        SearchBudget.refresh(sl.getServer());
        while (true) {
            if (jobIndex >= jobs.size()) {
                r.setState(TaskState.SUCCESS);
                return r.getState();
            }
            Job job = jobs.get(jobIndex);
            ChunkPos candidate = pendingCandidate != null ? pendingCandidate : job.next();
            pendingCandidate = null;
            if (candidate == null) {
                jobIndex++;
                continue;
            }
            if (!SearchBudget.tryCheck()) {
                pendingCandidate = candidate;
                return r.getState();
            }
            Boolean hit = checkCandidate(sl, job, candidate);
            if (hit == null) {
                pendingCandidate = candidate;
                return r.getState();
            }
            if (hit) {
                consider(job.placement.getLocatePos(candidate));
                jobIndex++;
            }
        }
    }

    private Boolean checkCandidate(ServerLevel sl, Job job, ChunkPos candidate) {
        ChunkAccess loaded = null;
        for (Structure structure : job.structures) {
            StructureCheckResult res = sl.structureManager()
                    .checkStructurePresence(candidate, structure, false);
            if (res == StructureCheckResult.START_NOT_PRESENT) continue;
            if (res == StructureCheckResult.START_PRESENT) return true;
            if (loaded == null) {
                if (!SearchBudget.tryChunkLoad()) return null;
                loaded = sl.getChunk(candidate.x, candidate.z, ChunkStatus.STRUCTURE_STARTS);
            }
            StructureStart start = sl.structureManager()
                    .getStartForStructure(SectionPos.bottomOf(loaded), structure, loaded);
            if (start != null && start.isValid()) return true;
        }
        return false;
    }

    private void consider(BlockPos pos) {
        double d = pos.distSqr(player.blockPosition());
        if (d < bestDistSqr) {
            bestDistSqr = d;
            best = pos;
        }
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("structure", r.structure);
        if (finalState == TaskState.SUCCESS && best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String dir = CompassUtil.compass(dx, dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", dir);
            data.put("horizontal_distance", dist);
            return TaskResult.ok("nearest " + r.structure + " at " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks). move_to the x/z (pick a sensible y for the terrain), "
                    + "then scan_blocks to find its actual blocks.", data);
        }
        data.put("found", false);
        String dim = player.level.dimension().location().getPath();
        return switch (finalState) {
            case SUCCESS -> {
                int searched = searchedRadiusBlocks();
                yield searched == 0
                        ? TaskResult.ok(r.structure + " does not generate IN THIS DIMENSION ("
                                + dim + ") — fortress/bastion: nether; end_city: the end; "
                                + "stronghold/village/mansion/monument: overworld", data)
                        : TaskResult.ok("no " + r.structure + " within ~" + searched
                                + " blocks of here (" + dim + ") — extremely unlucky seed; "
                                + "travel a few thousand blocks and retry", data);
            }
            case TIMEOUT -> TaskResult.timeout("search deadline hit after covering ~"
                    + searchedRadiusBlocks() + " blocks outward with no " + r.structure
                    + " — it is at least that far. Retrying immediately is fine (results "
                    + "are cached, the search resumes fast), or travel toward unexplored "
                    + "land first");
            case CANCELLED -> TaskResult.cancelled("locate_structure interrupted");
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    private int searchedRadiusBlocks() {
        int max = 0;
        for (Job job : jobs) {
            if (job.spread == null) continue;
            int rings = Math.min(job.ring, job.maxRing);
            max = Math.max(max, rings * job.spread.spacing() * 16);
        }
        return max;
    }

}
