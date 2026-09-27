package com.dwinovo.numen.pathing.world;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 一格方块的碰撞箱,拆成若干个长方体,坐标相对这一格的最小角。第 0 层的落脚、净空、迈步全部从这里取几何,
 * 不另有任何按方块种类写的形状表。
 *
 * <p>绝大多数方块的碰撞箱只由方块状态决定,按状态缓存:状态是驻留对象,表键就是状态本身,算一次、之后只读,
 * 多个搜索线程同时查是安全的。少数方块的碰撞箱随世界或身体变化,原版把它们标成 dynamic shape
 * ({@link Semantics#dynamicCollision}):脚手架与细雪看身体在不在它上面,竹子与滴水石锥按坐标偏移,潜影盒看开没开盖,
 * 移动中的活塞看方块实体。这些不进缓存,每次按坐标和身体的脚高向原版现问。
 *
 * <p>细雪要看身体是谁,原版的碰撞上下文里没有实体可给,它就一律答"陷进去"。身体托得住细雪时
 * ({@link BodyStats#walksOnPowderSnow}),这里照原版 {@code PowderSnowBlock.getCollisionShape} 的同一条规则回答:
 * 脚在它顶面之上就是一整块,否则是空的。原版另有"下落超过 2.5 格时被细雪接住"一条,看的是实体的下落距离,
 * 身体不在这里,不算。
 *
 * <p>穿冰霜行者的身体({@link BodyStats#frostWalker})走到静水边,原版把它脚下那一层一圈上面是空气的静水源冻成霜冰;
 * 这里对它把这样的水面答成一整块。
 */
final class Boxes {

    private static final AABB[] NONE = new AABB[0];
    private static final AABB[] FULL = {new AABB(0, 0, 0, 1, 1, 1)};
    private static final ConcurrentHashMap<BlockState, AABB[]> CACHE = new ConcurrentHashMap<>();

    private Boxes() {}

    /**
     * 这一格对这具身体的碰撞箱。
     *
     * @param feetY 身体的脚此刻(或设想中)的绝对高度:碰撞箱随身体变化的方块按它回答"身体在不在它上面"
     */
    static AABB[] at(BlockGetter level, BodyStats body, int x, int y, int z, BlockState state, double feetY) {
        if (body.walksOnPowderSnow() && state.is(Blocks.POWDER_SNOW)) {
            // 原版 isAbove:脚底高过顶面减去同一个容差
            return feetY > y + 1 - Footing.EPSILON ? FULL : NONE;
        }
        if (body.frostWalker() && freezes(level, x, y, z, state)) {
            return FULL;
        }
        if (Semantics.dynamicCollision(state)) {
            return split(state.getCollisionShape(level, new BlockPos(x, y, z), bodyAt(feetY)));
        }
        // 与原版给这类状态缓存碰撞箱的是同一次调用:不看世界、不看身体
        return CACHE.computeIfAbsent(state, s -> split(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
    }

    /**
     * 冰霜行者冻得住这一格:原版的冰霜行者只把上面是空气的静水源冻成霜冰(走在它旁边的地上时,脚下那一层一圈都冻上),
     * 所以对穿着它的身体,这样的水面就是一块能站的冰。
     */
    private static boolean freezes(BlockGetter level, int x, int y, int z, BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSource()
                && level.getBlockState(new BlockPos(x, y + 1, z)).isAir();
    }

    private static AABB[] split(VoxelShape shape) {
        if (shape.isEmpty()) {
            return NONE;
        }
        List<AABB> boxes = shape.toAabbs();
        return boxes.toArray(new AABB[0]);
    }

    /**
     * 设想中脚在 {@code feetY} 的身体:不下蹲、手里没拿东西、不能站在流体上,与原版给玩家的碰撞上下文同一套判"在上面"
     * 的算法。没有实体可给,细雪因此按"不是穿皮靴的实体"回答——身体陷进去。
     */
    private static CollisionContext bodyAt(double feetY) {
        return new EntityCollisionContext(false, feetY, ItemStack.EMPTY, fluid -> false, null) {};
    }
}
