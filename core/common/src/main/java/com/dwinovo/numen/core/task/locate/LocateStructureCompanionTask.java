package com.dwinovo.numen.core.task.locate;
import com.dwinovo.numen.core.task.IdSuggest;
import com.dwinovo.numen.core.task.CompassUtil;
import com.dwinovo.numen.core.scan.SearchBudget;
import com.dwinovo.numen.core.scan.RingSpiral;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Goal for {@code locate_structure}: find the nearest instance of a structure
 * (by id) or structure family (by {@code #tag}) in the entity's CURRENT
 * dimension — vanilla {@code /locate structure} semantics, but <b>time-sliced
 * across ticks instead of one synchronous call</b>.
 *
 * <h2>Why not {@code findNearestMapStructure}</h2>
 * The vanilla helper walks its whole candidate spiral inside one call; for
 * unlucky seeds / sparse structures the chunk-load fallbacks inside it stall
 * the server tick. Explorer's Compass (MattCzyr) solved this with budgeted
 * tick workers; this goal adapts that pattern to our already-tick-sliced task
 * machinery:
 * <ul>
 *   <li>candidate chunks come from the placement math itself
 *       ({@link RandomSpreadStructurePlacement#getPotentialStructureChunk} on
 *       an expanding ring spiral; {@code getRingPositionsFor} for concentric
 *       rings like the stronghold) — never a blind chunk walk;</li>
 *   <li>presence is checked with
 *       {@link net.minecraft.world.level.StructureManager#checkStructurePresence}
 *       (a cached lookup that does NOT generate chunks);</li>
 *   <li><b>一块区块都不加载</b>——见 {@code checkCandidate}:
 *       {@code CHUNK_LOAD_NEEDED} 本身就是肯定答案,补一句排除区判据即可,
 *       原版那次 {@code STRUCTURE_STARTS} 加载对我们纯属多余。</li>
 * </ul>
 * 于是这个搜索对服务端的全部成本就是 {@link SearchBudget} 框住的那点 CPU:
 * 最坏情况是答案晚几 tick,而不是主线程卡在世界生成上等到看门狗把服务器杀掉。
 */
public final class LocateStructureCompanionTask extends AbstractCompanionTask<LocateStructureTaskRecord> {

    /**
     * Search radius in placement-region RINGS, exactly vanilla /locate's
     * radius unit (one ring = one region = {@code spacing} chunks, so the
     * covered distance scales with the structure's rarity: fortress ≈ 43k
     * blocks, village ≈ 54k). The global budget + the task deadline bound the
     * actual work; a search that exhausts its deadline reports how far it got.
     */
    private static final int SEARCH_RADIUS_RINGS = 100;

    /** One placement's candidate stream + the structures that use it. */
    private static final class Job {
        final StructurePlacement placement;
        final List<Structure> structures = new ArrayList<>(1);
        // Spiral state (RandomSpread): region-grid ring walk around the entity.
        RandomSpreadStructurePlacement spread;
        long seed;
        int centerRegX, centerRegZ, maxRing, ring, perimIdx;
        // Ring-list state (ConcentricRings, e.g. stronghold).
        List<ChunkPos> ringPositions;

        Job(StructurePlacement placement) {
            this.placement = placement;
        }

        /** Next candidate chunk, or null when this job is exhausted. */
        ChunkPos next() {
            if (spread == null) return null;     // ring jobs are consumed in onStart
            while (ring <= maxRing) {
                if (perimIdx >= RingSpiral.perimeter(ring)) {
                    ring++;
                    perimIdx = 0;
                    continue;
                }
                int[] d = RingSpiral.offset(ring, perimIdx++);
                // getPotentialStructureChunk takes CHUNK coords and floorDivs by
                // spacing itself — scale the region index back to chunk scale.
                return spread.getPotentialStructureChunk(seed,
                        (centerRegX + d[0]) * spread.spacing(),
                        (centerRegZ + d[1]) * spread.spacing());
            }
            return null;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private int jobIndex;
    private ChunkPos pendingCandidate;     // budget ran out mid-candidate; resume here
    private BlockPos best;
    private double bestDistSqr = Double.MAX_VALUE;
    private String failReason = "not on a server level";

    public LocateStructureCompanionTask(NumenPlayer player, LocateStructureTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        jobs.clear();
        jobIndex = 0;
        pendingCandidate = null;
        best = null;
        bestDistSqr = Double.MAX_VALUE;

        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return;
        }
        List<Holder<Structure>> holders = resolveStructures(sl, r.structure.trim());
        if (holders == null) {
            fail(failReason, FailureType.UNKNOWN);   // failReason set by resolveStructures
            return;
        }
        if (holders.isEmpty()) {
            return;   // valid tag, nothing in it → onTick reports "not found" (SUCCESS)
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
                // Ring positions ARE where these structures generate (vanilla
                // computes + caches them once per world) — pick the nearest
                // directly, no presence checks or chunk loads needed.
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions != null) {
                    for (ChunkPos cp : positions) {
                        consider(job.placement.getLocatePos(cp));
                    }
                }
            }
        }
        // No jobs and no ring hit → onTick finishes immediately as "not found" (e.g.
        // fortress searched from the overworld: no placement in this dimension).
    }

    /** @return resolved holders, empty list for a valid-but-empty tag, or null on bad input. */
    private List<Holder<Structure>> resolveStructures(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> out = new ArrayList<>();
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid structure tag: " + arg;
                return null;
            }
            var set = registry.get(TagKey.create(Registries.STRUCTURE, tagId));
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
                : registry.get(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            if (id != null && isBiomeId(sl, id)) {
                failReason = arg + " is a BIOME, not a structure — call "
                        + "locate_biome(biome=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.listElements().map(ref -> ref.key().location()), arg);
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
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, id)).isPresent();
    }

    private static boolean isBiomeTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(TagKey.create(Registries.BIOME, tagId)).isPresent();
    }

    @Override
    protected TaskState onTick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        // GLOBAL budget: shared by every searching companion on the server, so
        // total per-tick search cost is a constant regardless of pet count.
        SearchBudget.refresh(sl.getServer());
        while (true) {
            if (jobIndex >= jobs.size()) {
                return TaskState.SUCCESS;
            }
            Job job = jobs.get(jobIndex);
            ChunkPos candidate = pendingCandidate != null ? pendingCandidate : job.next();
            pendingCandidate = null;
            if (candidate == null) {
                jobIndex++;
                continue;
            }
            if (!SearchBudget.tryCheck()) {
                pendingCandidate = candidate;   // pool drained — resume next tick
                return TaskState.RUNNING;
            }
            if (checkCandidate(sl, job, candidate)) {
                consider(job.placement.getLocatePos(candidate));
                jobIndex++;   // ring order ⇒ first hit is this job's nearest
            }
        }
    }

    /**
     * 这一格上会不会长出这个任务要找的结构。
     *
     * <h2>一块区块都不加载</h2>
     * {@code checkStructurePresence} 三种结果里,前两种来自内存缓存与存档 NBT,本来就是零成本的
     * 权威答案。第三种 {@code CHUNK_LOAD_NEEDED} 听着像"得去加载",其实<b>它本身就是肯定答案</b>
     * ——原版只在 {@code canCreateStructure()} 为真之后才返回它,而那个方法就是
     * {@code findValidGenerationPoint(...).isPresent()},跟真正生成时用的是同一个判据。
     *
     * <p>原版接着还去 {@code getChunk(STRUCTURE_STARTS)},只为两件我们不需要的事:拿
     * {@code start.getChunkPos()} 当坐标(而它在 {@code START_PRESENT} 快路径上用的就是
     * {@code placement.getLocatePos(chunkPos)},同一个来源),以及 {@code skipKnownStructures}
     * 时注册引用(我们传 false)。所以那一步对我们是纯粹多余的——而它正是同步生成区块的那一处:
     * 未加载的区块会当场跑一遍世界生成,主线程干等,单 tick 超六十秒就被看门狗判定崩溃。
     *
     * <p>代价是要自己补 {@code isStructureChunk}:{@code checkStart} 只查了
     * {@code applyAdditionalChunkRestrictions},漏了排除区那一项(原版靠 getChunk 里真跑一遍
     * 生成来兜)。不补就会多报被排除区挡掉的结构。
     */
    private boolean checkCandidate(ServerLevel sl, Job job, ChunkPos candidate) {
        for (Structure structure : job.structures) {
            StructureCheckResult res = sl.structureManager()
                    .checkStructurePresence(candidate, structure, job.placement, false);
            if (res == StructureCheckResult.START_NOT_PRESENT) continue;
            if (res == StructureCheckResult.START_PRESENT) return true;
            // CHUNK_LOAD_NEEDED:生成判据已经点头了,只差排除区这一项。
            if (job.placement.isStructureChunk(
                    sl.getChunkSource().getGeneratorState(), candidate.x, candidate.z)) {
                return true;
            }
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

    /** Search tasks paint no path overlay — nothing to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("structure", r.structure);
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", CompassUtil.compass(dx, dz));
            data.put("horizontal_distance", dist);
        } else {
            data.put("found", false);
        }
        return data;
    }

    @Override
    protected String successMessage() {
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String dir = CompassUtil.compass(dx, dz);
            return "nearest " + r.structure + " at " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks). goto the x/z (pick a sensible y for the terrain), "
                    + "then scan_blocks to find its actual blocks.";
        }
        String dim = player.level().dimension().location().getPath();
        int searched = searchedRadiusBlocks();
        return searched == 0
                ? r.structure + " does not generate IN THIS DIMENSION ("
                        + dim + ") — fortress/bastion: nether; end_city: the end; "
                        + "stronghold/village/mansion/monument: overworld"
                : "no " + r.structure + " within ~" + searched
                        + " blocks of here (" + dim + ") — extremely unlucky seed; "
                        + "travel a few thousand blocks and retry";
    }

    @Override
    protected String timeoutMessage() {
        return "search deadline hit after covering ~"
                + searchedRadiusBlocks() + " blocks outward with no " + r.structure
                + " — it is at least that far. Retrying immediately is fine (results "
                + "are cached, the search resumes fast), or travel toward unexplored "
                + "land first";
    }

    @Override
    protected String cancelledMessage() {
        return "locate_structure interrupted";
    }

    /** How far outward (blocks) the random-spread spirals have covered so far. */
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
