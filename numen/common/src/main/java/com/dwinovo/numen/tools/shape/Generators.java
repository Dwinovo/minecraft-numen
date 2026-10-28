package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.CoralTreeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 让原版(和模组)的生成器跑在 {@link RecordedLevel} 上,交回"要写什么":地物({@code //feature})、结构({@code //structure})、树林({@code //forest})
 * 靠注册表里的生成器本身;花草({@code //flora})与南瓜田({@code //pumpkins})是 WorldEdit 自己的算法。落点判据与 WorldEdit 一致:每列从选区最高处往下找
 * 第一块非空气的方块当地面,按密度掷骰子。真实世界一格不动。
 */
final class Generators {

    private Generators() {}

    /** 树林默认的树。 */
    static final String DEFAULT_TREE = "minecraft:oak_checked";
    /** WorldEdit 自己的两种树:随机挑一种,和它那棵简陋的松树。 */
    static final String RANDOM_TREE = "worldedit:random";
    static final String PINE_TREE = "worldedit:pine";
    /** 每个落点最多试几次(同 WorldEdit:原版的树常因为空间不够长不出来)。 */
    private static final int TREE_TRIES = 10;

    /** 生成的结果:要写的方块,和 Cells 装不下的东西。 */
    record Grown(List<BlockAt> blocks, List<RecordedLevel.Lost> lost) {

        static Grown of(RecordedLevel level) {
            return new Grown(level.blocks(), level.lost());
        }
    }

    // ---- 注册表 ----

    private static ResourceLocation id(String text, String what, String hint) {
        ResourceLocation id = ResourceLocation.tryParse(text.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, what + " \"" + text + "\" is not a valid id", hint(hint));
        }
        return id;
    }

    private static String hint(String function) {
        return Call.help(function);
    }

    /** 注册表里是树的放置地物:底下的地物是树、珊瑚树(模组注册的树也在里面)。 */
    static List<String> treeIds(ServerLevel level) {
        Registry<PlacedFeature> placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        List<String> out = new ArrayList<>();
        for (var entry : placed.entrySet()) {
            var feature = entry.getValue().feature().value().feature();
            if (feature instanceof TreeFeature || feature instanceof CoralTreeFeature) {
                out.add(entry.getKey().location().toString());
            }
        }
        out.sort(null);
        return out;
    }

    private static PlacedFeature tree(ServerLevel level, String text) {
        ResourceLocation id = id(text, "tree", "numen.shape.forest");
        PlacedFeature placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).get(id);
        if (placed == null || !treeIds(level).contains(id.toString())) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "\"" + text + "\" is no tree of this game; trees are " + treeIds(level)
                    + " (and " + PINE_TREE + ", " + RANDOM_TREE + ")", hint("numen.shape.forest"));
        }
        return placed;
    }

    // ---- 地物、结构 ----

    private static ApiError failed(String what, Throwable cause) {
        return new ApiError(ErrorKind.FAILED, what + " could not be run on a recorded level: " + cause, null);
    }

    /** {@code //feature}:一个原版配置地物种在 at。 */
    static Grown feature(LoadedBlocks w, BlockPos at, String text) {
        ServerLevel level = w.level();
        ResourceLocation id = id(text, "feature", "numen.shape.feature");
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(id);
        if (feature == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no feature \"" + text + "\" in this game", hint("numen.shape.feature"));
        }
        RecordedLevel recorded = new RecordedLevel(w);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        boolean placed;
        try {
            placed = recorded.attempt(() -> feature.place(recorded.level(), generator, recorded.random(), at));
        } catch (ClassCastException | UnsupportedOperationException e) {
            throw failed("the feature " + id, e);
        }
        if (!placed) {
            throw new ApiError(ErrorKind.FAILED, "the feature " + id + " found no place to grow at " + at.toShortString()
                    + " (the game's own placement rules said no)", null);
        }
        return Grown.of(recorded);
    }

    /** {@code //structure}:一个原版结构在 at 所在区块生成(高度由区块生成器定,同 WorldEdit)。 */
    static Grown structure(LoadedBlocks w, BlockPos at, String text) {
        ServerLevel level = w.level();
        ResourceLocation id = id(text, "structure", "numen.shape.structure");
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure structure = registry.get(id);
        if (structure == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no structure \"" + text + "\" in this game",
                    hint("numen.shape.structure"));
        }
        RecordedLevel recorded = new RecordedLevel(w);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        WorldGenLevel view = recorded.level();
        ChunkPos chunkPos = new ChunkPos(at);
        boolean placed;
        try {
            placed = recorded.attempt(() -> {
                StructureStart start = structure.generate(
                        level.registryAccess(), generator, generator.getBiomeSource(), level.getChunkSource().randomState(),
                        level.getStructureManager(), level.getSeed(), chunkPos, 0, view, biome -> true);
                if (!start.isValid()) {
                    return false;
                }
                BoundingBox box = start.getBoundingBox();
                ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
                ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
                ChunkPos.rangeClosed(min, max).forEach(each -> start.placeInChunk(view, level.structureManager(), generator,
                        recorded.random(), new BoundingBox(each.getMinBlockX(), level.getMinBuildHeight(), each.getMinBlockZ(),
                                each.getMaxBlockX(), level.getMaxBuildHeight(), each.getMaxBlockZ()), each));
                return true;
            });
        } catch (ClassCastException | UnsupportedOperationException e) {
            throw failed("the structure " + id, e);
        }
        if (!placed) {
            throw new ApiError(ErrorKind.FAILED, "the structure " + id + " has no valid layout at chunk " + chunkPos.x + ", "
                    + chunkPos.z + " (the game's own generator said no)", null);
        }
        return Grown.of(recorded);
    }

    // ---- 按列落点:forest、flora、pumpkins ----

    /** 每一列要不要落点:先掷骰子,再从 maxY 往下找第一块非空气的方块当地面;顶上一格就不是空气的列在地下,跳过。 */
    interface Ground {

        void apply(RecordedLevel level, BlockPos ground);
    }

    private static void eachColumn(RecordedLevel level, List<BlockPos> columns, int minY, int maxY, double density, Ground ground) {
        LoadedBlocks w = level.real();
        int top = Math.min(maxY, w.maxY());
        int bottom = Math.max(minY, w.minY());
        for (BlockPos column : columns) {
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > density) {
                continue;
            }
            if (top + 1 <= w.maxY() && !level.at(new BlockPos(column.getX(), top + 1, column.getZ())).isAir()) {
                continue;
            }
            for (int y = top; y >= bottom; y--) {
                BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                if (!level.at(pos).isAir()) {
                    ground.apply(level, pos);
                    break;
                }
            }
        }
    }

    /** {@code //forest}:每列按 density(0 到 1)落一棵树。 */
    static Grown forest(LoadedBlocks w, List<BlockPos> columns, int minY, int maxY, String treeText, double density) {
        RecordedLevel recorded = new RecordedLevel(w);
        TerrainReads.requireAround(w, columns, 0);
        Ground ground;
        if (treeText.equals(PINE_TREE)) {
            ground = (rec, pos) -> rec.attempt(() -> pine(rec, pos.above()));
        } else {
            ServerLevel level = w.level();
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            boolean any = treeText.equals(RANDOM_TREE);
            List<String> trees = any ? treeIds(level) : List.of();
            PlacedFeature fixed = any ? null : tree(level, treeText);
            ground = (rec, pos) -> {
                PlacedFeature placed = fixed != null ? fixed : tree(level, trees.get(rec.random().nextInt(trees.size())));
                BlockPos above = pos.above();
                for (int i = 0; i < TREE_TRIES; i++) {
                    try {
                        if (rec.attempt(() -> placed.place(rec.level(), generator, rec.random(), above))) {
                            return;
                        }
                    } catch (ClassCastException | UnsupportedOperationException e) {
                        throw failed("the tree " + treeText, e);
                    }
                }
            };
        }
        eachColumn(recorded, columns, minY, maxY, density, ground);
        return Grown.of(recorded);
    }

    /** WorldEdit 的"松树":一小截树干,往上一层层的橡木叶子;只在空气里放。 */
    private static boolean pine(RecordedLevel level, BlockPos base) {
        RandomSource random = level.random();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        int trunk = 3 + random.nextInt(2);
        int crown = 8 + random.nextInt(5);
        for (int i = 0; i < trunk; i++) {
            if (!ifAir(level, base.above(i), log)) {
                return false;
            }
        }
        BlockPos top = base.above(trunk);
        for (int i = 0; i < crown; i++) {
            BlockPos center = top.above(i);
            ifAir(level, center, log);
            boolean edge = i == 0 || i == crown - 1;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int ring = Math.max(Math.abs(dx), Math.abs(dz));
                    if (ring == 1) {
                        byChance(level, center.offset(dx, 0, dz), leaves, edge ? 0.6 : 1);
                    } else if (ring == 2 && !edge) {
                        // 外圈四角各试两次,角上的叶子更常有
                        int tries = Math.abs(dx) == 2 && Math.abs(dz) == 2 ? 2 : 1;
                        for (int t = 0; t < tries; t++) {
                            byChance(level, center.offset(dx, 0, dz), leaves, 0.6);
                        }
                    }
                }
            }
        }
        ifAir(level, top.above(crown), leaves);
        return true;
    }

    private static boolean ifAir(RecordedLevel level, BlockPos pos, BlockState state) {
        if (!level.at(pos).isAir()) {
            return false;
        }
        level.set(pos, state);
        return true;
    }

    /** 掷一次 [0,1) 的数,不超过 chance 就往这格放(仍只放进空气)。 */
    private static void byChance(RecordedLevel level, BlockPos pos, BlockState state, double chance) {
        if (level.random().nextDouble() <= chance) {
            ifAir(level, pos, state);
        }
    }

    /** {@code //flora}:草地上长草与小花,沙子上长枯灌木与仙人掌(其余的地面不长);一列一格,落在地面上一格。 */
    static List<BlockAt> flora(LoadedBlocks w, List<BlockPos> columns, int minY, int maxY, double density) {
        RecordedLevel recorded = new RecordedLevel(w);
        TerrainReads.requireAround(w, columns, 0);
        eachColumn(recorded, columns, minY, maxY, density, (rec, pos) -> {
            BlockState ground = rec.at(pos);
            if (ground.is(Blocks.GRASS_BLOCK)) {
                rec.set(pos.above(), weighted(rec.random(), new Block[]{Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.DANDELION},
                        new int[]{300, 5, 5}));
            } else if (ground.is(Blocks.SAND)) {
                rec.set(pos.above(), weighted(rec.random(), new Block[]{Blocks.DEAD_BUSH, Blocks.CACTUS, Blocks.AIR},
                        new int[]{30, 20, 300}));
            }
        });
        return recorded.blocks();
    }

    private static BlockState weighted(RandomSource random, Block[] blocks, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int pick = random.nextInt(total);
        for (int i = 0; i < blocks.length; i++) {
            pick -= weights[i];
            if (pick < 0) {
                return blocks[i].defaultBlockState();
            }
        }
        return blocks[blocks.length - 1].defaultBlockState();
    }

    /** {@code //pumpkins}:草地上零星的"南瓜田":一圈叶子藤蔓、橡木和南瓜,在 at 周围 apothem 格、高低 -5 到 +10 层里,密度 2%。 */
    static List<BlockAt> pumpkins(LoadedBlocks w, BlockPos at, int apothem) {
        List<BlockPos> columns = new ArrayList<>();
        for (int x = at.getX() - apothem; x <= at.getX() + apothem; x++) {
            for (int z = at.getZ() - apothem; z <= at.getZ() + apothem; z++) {
                columns.add(new BlockPos(x, at.getY(), z));
            }
        }
        TerrainReads.requireAround(w, columns, 0);
        RecordedLevel recorded = new RecordedLevel(w);
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        eachColumn(recorded, columns, at.getY() - 5, at.getY() + 10, 0.02, (rec, ground) -> {
            BlockPos patch = rec.at(ground).isAir() ? ground : ground.above();
            if (!rec.at(patch.below()).is(Blocks.GRASS_BLOCK)) {
                return;
            }
            rec.set(patch, leaf);
            vine(rec, patch, patch.south(), leaf);
            vine(rec, patch, patch.north(), leaf);
            vine(rec, patch, patch.east(), leaf);
            vine(rec, patch, patch.west(), leaf);
        });
        return recorded.blocks();
    }

    /** 南瓜藤的一段:t 选下表的一行,依次是再长一段藤的起点、可能有的橡木(高低随 h)、南瓜,偏移相对这段藤落定的那一格。 */
    private static final int[][][] VINE_TURNS = {
            {{1, 0, 0}, {1, 0, -1}, {0, 0, -1}},
            {{0, 0, 1}, {1, 0, 0}, {1, 0, 1}},
            {{0, 0, -1}, {-1, 0, 0}, {-1, 0, 1}},
            {{-1, 0, 0}, {-1, 0, -1}, {-1, 0, -1}},
    };

    /** 从 start 长一段藤,离 base 超过 4 格或 start 不空就不长;先长完下一段,再放这一段的橡木和南瓜。 */
    private static void vine(RecordedLevel level, BlockPos base, BlockPos start, BlockState leaf) {
        if (start.distSqr(base) > 16 || !level.at(start).isAir()) {
            return;
        }
        // 悬空的藤往下落,最多两格
        BlockPos at = start;
        for (int i = 0; i < 2 && level.at(at.below()).isAir(); i++) {
            at = at.below();
        }
        ifAir(level, at, leaf);
        RandomSource random = level.random();
        int[][] turn = VINE_TURNS[random.nextInt(4)];
        int h = random.nextInt(3) - 1;
        if (random.nextBoolean()) {
            vine(level, base, at.offset(turn[0][0], turn[0][1], turn[0][2]), leaf);
        }
        if (random.nextBoolean()) {
            ifAir(level, at.offset(turn[1][0], h, turn[1][2]), Blocks.OAK_LOG.defaultBlockState());
        }
        ifAir(level, at.offset(turn[2][0], turn[2][1], turn[2][2]), Blocks.PUMPKIN.defaultBlockState());
    }

}
