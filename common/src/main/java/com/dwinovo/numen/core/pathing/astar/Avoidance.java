package com.dwinovo.numen.core.pathing.astar;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * 球形成本惩罚源:生物周围(半径 8,系数 1.5)与刷怪笼周围(半径 16,
 * 系数 2.0)叠加进 Favoring 表,让 A* 倾向绕开。受 NavSettings.avoidance
 * 开关控制(默认关,关闭时 create 返回空表,行为不变)。
 *
 * <p>球内成本乘以系数:节点落在球内时 actionCost *= coefficient。多个球
 * 叠乘(球内同时有生物与刷怪笼时乘两次)。蜘蛛在白天光照高时不算威胁;
 * 末影人未激怒、僵尸猪灵未被打过的不算威胁——与 Baritone Avoidance 同。
 */
public final class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    /** (x,y,z) 落在球内时返回 coefficient,否则 1.0。 */
    public double coefficient(int x, int y, int z) {
        int dx = x - centerX;
        int dy = y - centerY;
        int dz = z - centerZ;
        return dx * dx + dy * dy + dz * dz <= radiusSq ? coefficient : 1.0D;
    }

    /**
     * 构造本 tick 的全部规避球。NavSettings.avoidance 关闭时返回空表
     * (行为变);开启时扫描玩家附近的生物与刷怪笼。
     */
    public static List<Avoidance> create(ServerPlayer player) {
        NavSettings s = NavSettings.get();
        if (!s.avoidance) {
            return List.of();
        }
        List<Avoidance> res = new ArrayList<>();
        double mobSpawnerCoeff = s.mobSpawnerAvoidanceCoefficient;
        double mobCoeff = s.mobAvoidanceCoefficient;
        BlockPos feet = player.blockPosition();
        int mobSpawnerRadius = s.mobSpawnerAvoidanceRadius;
        int mobRadius = s.mobAvoidanceRadius;
        if (mobSpawnerCoeff != 1.0D) {
            for (BlockPos pos : spawnersInLoadedChunks(player, feet)) {
                res.add(new Avoidance(pos, mobSpawnerCoeff, mobSpawnerRadius));
            }
        }
        if (mobCoeff != 1.0D) {
            // 遍历该维度全部已加载实体(不设距离窗:路径中段远处的
            // 敌对生物同样要计入惩罚球)
            for (var entity : ((ServerLevel) player.level()).getAllEntities()) {
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                if (mob instanceof Spider && player.getLightLevelDependentMagicValue() >= 0.5) {
                    continue; // 蜘蛛在明亮处不主动威胁
                }
                if (mob instanceof EnderMan enderMan && !enderMan.isCreepy()) {
                    continue; // 未激怒的末影人不威胁
                }
                if (mob instanceof ZombifiedPiglin piglin && piglin.getLastHurtByMob() == null) {
                    continue; // 未被打过的僵尸猪灵不威胁
                }
                res.add(new Avoidance(mob.blockPosition(), mobCoeff, mobRadius));
            }
        }
        return res;
    }

    /** 扫描周围已加载区块半径(16 区块 = 256 格)。 */
    private static final int SPAWNER_CHUNK_SCAN_RADIUS = 16;

    /**
     * 玩家周围已加载区块里的全部刷怪笼位置。逐区块读方块实体表
     * (map 遍历,不逐格查询),未加载的区块跳过。
     */
    private static List<BlockPos> spawnersInLoadedChunks(ServerPlayer player, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        ServerLevel level = (ServerLevel) player.level();
        int centerCx = center.getX() >> 4;
        int centerCz = center.getZ() >> 4;
        for (int cx = centerCx - SPAWNER_CHUNK_SCAN_RADIUS; cx <= centerCx + SPAWNER_CHUNK_SCAN_RADIUS; cx++) {
            for (int cz = centerCz - SPAWNER_CHUNK_SCAN_RADIUS; cz <= centerCz + SPAWNER_CHUNK_SCAN_RADIUS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (entry.getValue() instanceof SpawnerBlockEntity) {
                        out.add(entry.getKey().immutable());
                    }
                }
            }
        }
        return out;
    }

    /** 把球形惩罚叠乘进 favoring 表(已有值乘以 coefficient)。 */
    public void applySpherical(it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = PathNode.longHash(centerX + x, centerY + y, centerZ + z);
                        map.put(hash, map.get(hash) * coefficient);
                    }
                }
            }
        }
    }
}