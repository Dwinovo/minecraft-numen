package com.dwinovo.numen.pathing.search;

import java.util.Optional;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;

/**
 * 搜索从哪个节点出发。身体的位置是连续的,节点是它脚所在的那一格(第 0 层 {@link Footing#cellOf});身体站在方块边沿、
 * 中心已经悬空时,那一格待不住,就从身体脚底压着的相邻几列里、同一个节点高度上待得住的那一列出发,取离身体中心最近的。
 * 这是"假起点"的唯一规则。
 */
public final class Origin {

    private Origin() {}

    /** 身体脚底中心在 {@code (x, feetY, z)} 时搜索的起点;身体脚底压着的每一列都待不住时为空。 */
    public static Optional<BlockPos> of(BlockGetter level, BodyStats body, double x, double feetY, double z) {
        int y = Footing.cellOf(feetY);
        BlockPos own = BlockPos.containing(x, y, z);
        if (Stance.at(level, body, own) != null) {
            return Optional.of(own);
        }
        double half = body.width() / 2;
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int cx = Mth.floor(x - half); cx <= Mth.floor(x + half); cx++) {
            for (int cz = Mth.floor(z - half); cz <= Mth.floor(z + half); cz++) {
                BlockPos node = new BlockPos(cx, y, cz);
                if (node.equals(own) || Stance.at(level, body, node) == null) {
                    continue;
                }
                double dx = cx + 0.5 - x;
                double dz = cz + 0.5 - z;
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    best = node;
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
