package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class LocateBiomeTaskGoal implements CompanionTask {

    private static final int SAMPLE_STEP_BLOCKS = 64;
    private static final int SEARCH_RADIUS_RINGS = 100;
    private static final int Y_STEP_BLOCKS = 64;

    private Predicate<Holder<Biome>> match;
    private BiomeSource biomeSource;
    private Climate.Sampler sampler;
    private int[] yBlocks;
    private int centerX, centerZ;
    private int ring, perimIdx;
    private boolean exhausted;
    private BlockPos best;
    private String failReason = "not on a server level";

    private final NumenPlayer player;
    private final LocateBiomeTaskRecord r;

    public LocateBiomeTaskGoal(NumenPlayer player, LocateBiomeTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        match = null;
        best = null;
        ring = 0;
        perimIdx = 0;
        exhausted = false;

        if (!(player.level instanceof ServerLevel sl)) {
            failReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return;
        }
        match = resolveBiomePredicate(sl, r.biome.trim());
        if (match == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        biomeSource = sl.getChunkSource().getGenerator().getBiomeSource();
        sampler = sl.getChunkSource().randomState().sampler();
        yBlocks = Mth.outFromOrigin(player.getBlockY(),
                sl.getMinBuildHeight() + 1, sl.getMaxBuildHeight(), Y_STEP_BLOCKS).toArray();
        centerX = player.getBlockX();
        centerZ = player.getBlockZ();
    }

    private Predicate<Holder<Biome>> resolveBiomePredicate(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().registryOrThrow(Registries.BIOME);
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid biome tag: " + arg;
                return null;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
            if (registry.getTag(tag).isEmpty()) {
                failReason = isStructureTag(sl, tagId)
                        ? arg + " is a STRUCTURE tag, not a biome tag — call "
                                + "locate_structure(structure=\"" + arg + "\") instead"
                        : "unknown biome tag: " + arg + " — try a biome id like "
                                + "minecraft:warped_forest, or tags like #minecraft:is_forest";
                return null;
            }
            return holder -> holder.is(tag);
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        if (id == null || !registry.containsKey(ResourceKey.create(Registries.BIOME, id))) {
            if (id != null && isStructureId(sl, id)) {
                failReason = arg + " is a STRUCTURE, not a biome — call "
                        + "locate_structure(structure=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.holders().map(ref -> ref.key().location()), arg);
            failReason = "unknown biome: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a biome id like minecraft:warped_forest / "
                                    + "minecraft:desert, or a tag like #minecraft:is_forest; "
                                    + "load_skill(world_atlas) lists every id");
            return null;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        return holder -> holder.is(key);
    }

    private static boolean isStructureId(ServerLevel sl, ResourceLocation id) {
        return sl.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .containsKey(ResourceKey.create(Registries.STRUCTURE, id));
    }

    private static boolean isStructureTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getTag(TagKey.create(Registries.STRUCTURE, tagId)).isPresent();
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
            if (exhausted) {
                r.setState(TaskState.SUCCESS);
                return r.getState();
            }
            if (!SearchBudget.tryBiomeSample()) {
                return r.getState();
            }
            BlockPos hit = sampleNext();
            if (hit != null) {
                best = hit;
                r.setState(TaskState.SUCCESS);
                return r.getState();
            }
        }
    }

    private BlockPos sampleNext() {
        while (perimIdx >= RingSpiral.perimeter(ring)) {
            ring++;
            perimIdx = 0;
            if (ring > SEARCH_RADIUS_RINGS) {
                exhausted = true;
                return null;
            }
        }
        int[] d = RingSpiral.offset(ring, perimIdx++);
        int x = centerX + d[0] * SAMPLE_STEP_BLOCKS;
        int z = centerZ + d[1] * SAMPLE_STEP_BLOCKS;
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        for (int y : yBlocks) {
            Holder<Biome> biome = biomeSource.getNoiseBiome(qx, QuartPos.fromBlock(y), qz, sampler);
            if (match.test(biome)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("biome", r.biome);
        String dim = player.level.dimension().location().getPath();
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
            return TaskResult.ok("nearest " + r.biome + " around " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks; accurate to ~" + SAMPLE_STEP_BLOCKS + "). move_to the "
                    + "x/z (pick a sensible y for the terrain), then confirm with "
                    + "scan_blocks or scan_nearby_entities.", data);
        }
        data.put("found", false);
        int searched = Math.min(ring, SEARCH_RADIUS_RINGS) * SAMPLE_STEP_BLOCKS;
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("no " + r.biome + " within ~" + searched
                    + " blocks IN THIS DIMENSION (" + dim + ") — check the biome's "
                    + "home dimension (warped_forest/soul_sand_valley: nether; most "
                    + "others: overworld) or travel a few thousand blocks and retry", data);
            case TIMEOUT -> TaskResult.timeout("biome search deadline hit after ~"
                    + searched + " blocks with no " + r.biome
                    + " — retrying immediately is fine, or travel first");
            case CANCELLED -> TaskResult.cancelled("locate_biome interrupted");
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

}
