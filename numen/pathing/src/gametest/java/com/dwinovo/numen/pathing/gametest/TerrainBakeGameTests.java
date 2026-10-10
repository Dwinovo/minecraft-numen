package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.TerrainWorld;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * 制作真实地形的固定存档(套件 {@code numen_pathing_terrain_bake},只在制作时手动选跑,见 terrain/README.md):
 * 在真实地形维度里把一个区域文件(32×32 个区块,{@code r.X.Z.mca})连同外面一圈邻居生成到 FULL,摆上几块哨兵方块,存盘,
 * 把这个区域文件、描述它的 {@code terrain.properties} 写进输出目录;另把整块地的勘测表写进运行目录的
 * {@code terrain-survey.txt}(挑路线用,不进仓库)。外面一圈邻居只为让区域边缘的树完整,它们的区域文件不留。
 */
public class TerrainBakeGameTests {

    private static final Logger LOG = LogUtils.getLogger();
    /** 区域的边长(区块)。 */
    private static final int REGION = 32;
    /** 票据距离:中心区块往外 17 圈(35×35)全部到 FULL,正好罩住区域和外面一圈。 */
    private static final int TICKET_DISTANCE = 18;
    /** 哨兵方块的高度:在地形之上的空气里,现场生成的地形不会有它。 */
    private static final int SENTINEL_Y = 310;

    @GameTest(template = "numen_pathing:pathing_empty", timeoutTicks = 2_000_000)
    public static void bake(GameTestHelper helper) {
        String out = System.getProperty("numen.terrain.bake.out");
        String[] region = System.getProperty("numen.terrain.bake.region", "0,0").split(",");
        if (out == null) {
            helper.fail("没给 -PbakeTerrain");
            return;
        }
        int rx = Integer.parseInt(region[0].trim());
        int rz = Integer.parseInt(region[1].trim());
        ServerLevel level = helper.getLevel().getServer().getLevel(TerrainWorld.DIMENSION);
        ChunkPos center = new ChunkPos(rx * REGION + REGION / 2 - 1, rz * REGION + REGION / 2 - 1);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, center, TICKET_DISTANCE, center);
        LOG.info("[terrain-bake] 种子 {},区域 r.{}.{},开始生成", level.getSeed(), rx, rz);
        helper.onEachTick(() -> {
            if (!generated(level, center)) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            try {
                save(helper, level, Path.of(out), rx, rz);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static boolean generated(ServerLevel level, ChunkPos center) {
        int r = TICKET_DISTANCE - 1;
        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 同一刻里:勘测、摆哨兵、存盘、拷出区域文件,存档在这一刻的样子就是勘测表上的样子。 */
    private static void save(GameTestHelper helper, ServerLevel level, Path out, int rx, int rz) throws IOException {
        int bx = rx * REGION * 16;
        int bz = rz * REGION * 16;
        StringBuilder survey = new StringBuilder();
        for (int z = bz; z < bz + REGION * 16; z++) {
            for (int x = bx; x < bx + REGION * 16; x++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                int ground = ground(chunk, x, z, top);
                survey.append(x).append(' ').append(z).append(' ').append(ground).append(' ')
                        .append(id(chunk.getBlockState(new BlockPos(x, ground, z)))).append(' ')
                        .append(top).append(' ').append(id(chunk.getBlockState(new BlockPos(x, top, z)))).append('\n');
            }
        }
        Files.writeString(Path.of("terrain-survey.txt"), survey.toString(), StandardCharsets.UTF_8);

        List<BlockPos> sentinels = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            BlockPos pos = new BlockPos(bx + i * 128 + 2, SENTINEL_Y, bz + i * 128 + 3);
            level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            sentinels.add(pos);
        }
        level.save(null, true, false);

        Path regionFile = TerrainWorld.regionDir(level.getServer()).resolve("r." + rx + "." + rz + ".mca");
        Path regionOut = out.resolve("region");
        Files.createDirectories(regionOut);
        try (var old = Files.list(regionOut)) {
            for (Path p : old.toList()) {
                Files.delete(p);
            }
        }
        Files.copy(regionFile, regionOut.resolve(regionFile.getFileName()));

        StringBuilder props = new StringBuilder("# 由 TerrainBakeGameTests 写出,制作方法见同目录 README.md\n");
        props.append("seed=").append(level.getSeed()).append('\n');
        props.append("dimension=").append(TerrainWorld.DIMENSION_ID).append('\n');
        props.append("region=").append(rx).append(',').append(rz).append('\n');
        for (int i = 0; i < sentinels.size(); i++) {
            BlockPos p = sentinels.get(i);
            props.append("sentinel.").append(i).append('=').append(p.getX()).append(',').append(p.getY()).append(',')
                    .append(p.getZ()).append(',').append(id(Blocks.GOLD_BLOCK.defaultBlockState())).append('\n');
        }
        Files.writeString(out.resolve("terrain.properties"), props.toString(), StandardCharsets.UTF_8);
        LOG.info("[terrain-bake] 存档已写到 {}({} 字节)", out.toAbsolutePath(), Files.size(regionFile));
        helper.succeed();
    }

    /** 地形本身:从顶往下找第一格不是空气、树叶、原木、可被替换的植物或流体的方块,返回它的 y。 */
    private static int ground(LevelChunk chunk, int x, int z, int top) {
        int y = top;
        BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
        while (y > chunk.getMinBuildHeight() && (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.canBeReplaced() || !state.getFluidState().isEmpty())) {
            y--;
            state = chunk.getBlockState(new BlockPos(x, y, z));
        }
        return y;
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
