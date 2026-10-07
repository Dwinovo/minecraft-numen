package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;

/**
 * 世界的边:身体能待在哪、手能动哪一格。寻路里判世界边界只有这一处。
 *
 * <p>两件事口径不同,都照原版:身体被世界边界当墙挡着,碰撞盒整个在边界里才待得住,高度不设限;动手(挖、放、交互)照
 * {@code Level.mayInteract} 看这一格在不在世界边界里,另外这一格得在建筑高度之内——之外既没有方块可挖,也放不下。
 */
public final class Bounds {

    private Bounds() {}

    /** 身体站在 {@code (x, z)} 这一列时,碰撞盒整个在世界边界里。 */
    public static boolean holdsBody(WorldBorder border, BodyStats body, int x, int z) {
        double half = body.width() / 2;
        return border.isWithinBounds(new AABB(x + 0.5 - half, 0, z + 0.5 - half, x + 0.5 + half, 0, z + 0.5 + half));
    }

    /** 手能不能动这一格:在世界边界里,且在建筑高度之内。 */
    public static boolean allowsEdit(WorldBorder border, LevelHeightAccessor heights, BlockPos pos) {
        return border.isWithinBounds(pos) && !heights.isOutsideBuildHeight(pos);
    }
}
