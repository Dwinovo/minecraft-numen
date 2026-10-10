package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * 真实地形可行性验证(临时,不是正式用例):GameTest 场地在主世界(超平坦,1×1×1 的空结构只负责计时与汇报),
 * 真实地形在数据包维度 {@code numen_test:terrain} 里,用例拿绝对坐标在那里跑。
 * 步骤:区域票据把 33×33 个区块(以原点为中心,半径 16)生成到 FULL 并计时 → 取中心 32×32 区块的地形指纹(写文件)
 * → 挑一段约 100 格、陆地上的起终点 → 拉起普通假玩家用现有寻路走一趟。
 */
public class TerrainSpikeGameTests {

    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceKey<Level> TERRAIN =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("numen_test:terrain"));
    /** 票据距离 17:以中心区块为 0,半径 16 以内(33×33)的区块都到 FULL。 */
    private static final int TICKET_DISTANCE = 17;
    private static final int RADIUS = TICKET_DISTANCE - 1;
    private static final int LENGTH = 100;

    private enum Phase { GENERATING, SPAWN_WAIT, WALKING, DONE }

    /** 生成 → 指纹 → 走一趟。 */
    @GameTest(template = "numen_pathing:pathing_empty", timeoutTicks = 2_000_000)
    public static void generates_and_walks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(TERRAIN);
        if (level == null) {
            helper.fail("数据包维度 numen_test:terrain 没有加载");
            return;
        }
        LOG.info("[terrain-spike] 维度已加载 seed={} 主世界seed={} 维度列表={}", level.getSeed(), helper.getLevel().getSeed(),
                helper.getLevel().getServer().levelKeys());
        Worlds.settle(level);
        ChunkPos center = new ChunkPos(0, 0);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, center, TICKET_DISTANCE, center);
        long startNanos = System.nanoTime();
        long startTick = helper.getTick();
        State s = new State();
        helper.onEachTick(() -> tick(helper, level, s, startNanos, startTick));
    }

    private static final class State {
        Phase phase = Phase.GENERATING;
        TestBody body;
        BlockPos from;
        BlockPos to;
        Navigation navigation;
        int walkTicks;
        int spawnWait;
        long walkStartNanos;
        float lowestHealth = Float.MAX_VALUE;
        long maxTickNanos;
    }

    private static void tick(GameTestHelper helper, ServerLevel level, State s, long startNanos, long startTick) {
        switch (s.phase) {
            case GENERATING -> {
                if (generated(level)) {
                    long ms = (System.nanoTime() - startNanos) / 1_000_000;
                    LOG.info("[terrain-spike] {}×{} 区块到 FULL:{} ms,{} 刻", 2 * RADIUS + 1, 2 * RADIUS + 1, ms,
                            helper.getTick() - startTick);
                    fingerprint(level);
                    pickRoute(level, s);
                    s.body = TestBody.spawn(level, "terrain_walker", s.from.getX() + 0.5, s.from.getY(), s.from.getZ() + 0.5);
                    s.body.getFoodData().setFoodLevel(20);
                    s.phase = Phase.SPAWN_WAIT;
                } else if (helper.getTick() % 200 == 0) {
                    LOG.info("[terrain-spike] 生成中 {} 刻 {} ms,已 FULL {} 个", helper.getTick() - startTick,
                            (System.nanoTime() - startNanos) / 1_000_000, countFull(level));
                }
                nap();
            }
            case SPAWN_WAIT -> {
                if (++s.spawnWait > Trial.SPAWN_INVULNERABILITY) {
                    Navigator navigator = Navigator.of(s.body,
                            new Ports(Trial.ALLOW_ALL, Trial.NO_MATERIALS, Threats.NONE));
                    s.navigation = navigator.drive(NavRequest.to(Goals.at(s.to), RouteSpec.defaults()));
                    s.walkStartNanos = System.nanoTime();
                    s.phase = Phase.WALKING;
                }
            }
            case WALKING -> {
                s.walkTicks++;
                NavStatus status = s.navigation.tick();
                while (s.navigation.waiting()) {
                    nap();
                    status = s.navigation.tick();
                }
                s.lowestHealth = Math.min(s.lowestHealth, s.body.getHealth());
                if (status.running()) {
                    if (s.walkTicks >= 3000) {
                        s.navigation.stop();
                        s.phase = Phase.DONE;
                        LOG.info("[terrain-spike] 走路超时 {} 刻,身体在 {}", s.walkTicks, s.body.blockPosition());
                        s.body.leave();
                        helper.fail("走了 3000 刻没收场,身体在 " + s.body.blockPosition() + " " + s.navigation);
                    }
                    return;
                }
                s.phase = Phase.DONE;
                long ms = (System.nanoTime() - s.walkStartNanos) / 1_000_000;
                LOG.info("[terrain-spike] 走完 状态={} 结局={} 从{}到{} 终点身体在{} 用了{}刻 墙钟{}ms 最低血量{} 步数{}",
                        status.state(), status.outcome(), s.from, s.to, s.body.blockPosition(), s.walkTicks, ms,
                        s.lowestHealth, s.navigation.report().ledger().steps());
                s.body.leave();
                if (status.state() == NavStatus.State.ARRIVED) {
                    helper.succeed();
                } else {
                    helper.fail("没到:" + status.state() + " " + status.outcome());
                }
            }
            case DONE -> {
            }
        }
    }

    private static void nap() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean generated(ServerLevel level) {
        return countFull(level) == (2 * RADIUS + 1) * (2 * RADIUS + 1);
    }

    private static int countFull(ServerLevel level) {
        int n = 0;
        for (int cx = -RADIUS; cx <= RADIUS; cx++) {
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    // ==================== 指纹 ====================

    /**
     * 中心 32×32 区块(区块坐标 -16..15,即方块 -256..255)每格的三层,各记高度(顶面方块的 y)与顶面方块 id:
     * OCEAN_FLOOR 与 WORLD_SURFACE(都带树叶、原木、植物与流体),以及 ground(自己从顶往下找:跳过空气、树叶、原木、
     * 可被替换的植物与流体,即地形本身)。按行写进文件({@code numen.terrain.out},
     * 默认 terrain-fingerprint.txt),两次运行可以逐格比;日志里给两层各自的整体 SHA-256,每个区块的写进 .chunks 文件。
     */
    private static void fingerprint(ServerLevel level) {
        try {
            MessageDigest groundTotal = MessageDigest.getInstance("SHA-256");
            MessageDigest floorTotal = MessageDigest.getInstance("SHA-256");
            MessageDigest surfaceTotal = MessageDigest.getInstance("SHA-256");
            StringBuilder out = new StringBuilder();
            StringBuilder chunkHashes = new StringBuilder();
            for (int cz = -16; cz < 16; cz++) {
                for (int cx = -16; cx < 16; cx++) {
                    MessageDigest groundOne = MessageDigest.getInstance("SHA-256");
                    MessageDigest floorOne = MessageDigest.getInstance("SHA-256");
                    MessageDigest surfaceOne = MessageDigest.getInstance("SHA-256");
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            String ground = ground(chunk, cx, cz, lx, lz);
                            String floor = layer(chunk, Heightmap.Types.OCEAN_FLOOR, cx, cz, lx, lz);
                            String surface = layer(chunk, Heightmap.Types.WORLD_SURFACE, cx, cz, lx, lz);
                            out.append(cx * 16 + lx).append(',').append(cz * 16 + lz).append(',').append(ground).append(',')
                                    .append(floor).append(',').append(surface).append('\n');
                            groundOne.update(ground.getBytes(StandardCharsets.UTF_8));
                            groundTotal.update(ground.getBytes(StandardCharsets.UTF_8));
                            floorOne.update(floor.getBytes(StandardCharsets.UTF_8));
                            floorTotal.update(floor.getBytes(StandardCharsets.UTF_8));
                            surfaceOne.update(surface.getBytes(StandardCharsets.UTF_8));
                            surfaceTotal.update(surface.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    chunkHashes.append(cx).append(',').append(cz).append(" ground ")
                            .append(HexFormat.of().formatHex(groundOne.digest(), 0, 6)).append(" floor ")
                            .append(HexFormat.of().formatHex(floorOne.digest(), 0, 6)).append(" surface ")
                            .append(HexFormat.of().formatHex(surfaceOne.digest(), 0, 6)).append('\n');
                }
            }
            Path file = Path.of(System.getProperty("numen.terrain.out", "terrain-fingerprint.txt"));
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            Files.writeString(Path.of(file + ".chunks"), chunkHashes.toString(), StandardCharsets.UTF_8);
            LOG.info("[terrain-spike] 地形指纹 ground={} floor={} surface={} 文件={}",
                    HexFormat.of().formatHex(groundTotal.digest()), HexFormat.of().formatHex(floorTotal.digest()),
                    HexFormat.of().formatHex(surfaceTotal.digest()), file.toAbsolutePath());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 地形本身:从 WORLD_SURFACE 往下找第一格不是空气、树叶、原木、可被替换的植物或流体的方块。 */
    private static String ground(LevelChunk chunk, int cx, int cz, int lx, int lz) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
        BlockState state = chunk.getBlockState(new BlockPos(cx * 16 + lx, y, cz * 16 + lz));
        while (y > chunk.getMinBuildHeight() && (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.canBeReplaced() || !state.getFluidState().isEmpty())) {
            y--;
            state = chunk.getBlockState(new BlockPos(cx * 16 + lx, y, cz * 16 + lz));
        }
        return y + "," + BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static String layer(LevelChunk chunk, Heightmap.Types type, int cx, int cz, int lx, int lz) {
        int h = chunk.getHeight(type, lx, lz);
        BlockState top = chunk.getBlockState(new BlockPos(cx * 16 + lx, h, cz * 16 + lz));
        return h + "," + BuiltInRegistries.BLOCK.getKey(top.getBlock());
    }

    // ==================== 挑一段路 ====================

    /**
     * 沿 x 方向 {@value #LENGTH} 格的直线,按"不是陆地的采样点数 + 两格内落差超过 2 的次数"打分,取分最低(同分取先扫到的)的一条。
     * 起点终点站在顶面上。
     */
    private static void pickRoute(ServerLevel level, State s) {
        int bestX = 0;
        int bestZ = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int x = -200; x <= 100; x += 4) {
            for (int z = -200; z <= 200; z += 4) {
                int score = badness(level, x, z);
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        s.from = new BlockPos(bestX, height(level, bestX, bestZ) + 1, bestZ);
        s.to = new BlockPos(bestX + LENGTH, height(level, bestX + LENGTH, bestZ) + 1, bestZ);
        LOG.info("[terrain-spike] 选定路线 {} -> {} 评分(越低越好,0 表示全程陆地且平缓){}", s.from, s.to, bestScore);
    }

    private static int badness(ServerLevel level, int x, int z) {
        if (!land(level, x, z) || !land(level, x + LENGTH, z)) {
            return Integer.MAX_VALUE - 1;
        }
        int bad = 0;
        int last = height(level, x, z);
        for (int d = 0; d <= LENGTH; d += 2) {
            if (!land(level, x + d, z)) {
                bad++;
            }
            int h = height(level, x + d, z);
            if (Math.abs(h - last) > 2) {
                bad++;
            }
            last = h;
        }
        return bad;
    }

    /** 顶面方块的 y(ChunkAccess.getHeight 给的是最高方块本身,不是它之上);站立的那一格是 +1。 */
    private static int height(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    /** 顶面是实心的干地:不是水、树叶、原木,海平面以上,头顶没有水。 */
    private static boolean land(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        BlockState top = chunk.getBlockState(new BlockPos(x, h, z));
        return h == floor && h > level.getSeaLevel() && top.getFluidState().isEmpty()
                && !top.is(BlockTags.LEAVES) && !top.is(BlockTags.LOGS);
    }
}
