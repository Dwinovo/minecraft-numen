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
 *
 * <h2>全空与整块</h2>
 * 绝大多数格的碰撞箱要么什么也没有,要么正好是这一格整块。算碰撞箱的同时就从同一份结果推出它是哪一类({@link Fill}):
 * 迈步要看的格全是这两类时按整数格子推导({@link Stepping}),其余照旧逐个碰撞箱比;两条路算出的结果完全相同,由单测对
 * 全部方块状态与全部方向逐一核对。落脚与净空一次只看一两格,按格分两条路实测没有更快,只走碰撞箱。
 */
final class Boxes {

    /** 一格的碰撞箱是哪一类。 */
    enum Fill {
        /** 没有碰撞箱。 */
        EMPTY,
        /** 正好一个碰撞箱,就是这一格整块。 */
        WHOLE,
        /** 别的形状。 */
        PARTIAL
    }

    /** 一格的碰撞箱,连同它是哪一类。 */
    record Shape(AABB[] boxes, Fill fill) {}

    private static final AABB[] NONE = new AABB[0];
    private static final AABB[] FULL = {new AABB(0, 0, 0, 1, 1, 1)};
    private static final Shape EMPTY = new Shape(NONE, Fill.EMPTY);
    private static final Shape WHOLE = new Shape(FULL, Fill.WHOLE);
    private static final ConcurrentHashMap<BlockState, Shape> CACHE = new ConcurrentHashMap<>();

    private Boxes() {}

    /**
     * 这一格对这具身体的碰撞箱。
     *
     * @param feetY 身体的脚此刻(或设想中)的绝对高度:碰撞箱随身体变化的方块按它回答"身体在不在它上面"
     */
    static AABB[] at(BlockGetter level, BodyStats body, int x, int y, int z, BlockState state, double feetY) {
        return shape(level, body, x, y, z, state, feetY).boxes();
    }

    /** 同 {@link #at},连同它是全空、整块还是别的形状。 */
    static Shape shape(BlockGetter level, BodyStats body, int x, int y, int z, BlockState state, double feetY) {
        if (body.walksOnPowderSnow() && state.is(Blocks.POWDER_SNOW)) {
            // 原版 isAbove:脚底高过顶面减去同一个容差
            return feetY > y + 1 - Footing.EPSILON ? WHOLE : EMPTY;
        }
        if (body.frostWalker() && freezes(level, x, y, z, state)) {
            return WHOLE;
        }
        if (Semantics.dynamicCollision(state)) {
            return classify(split(state.getCollisionShape(level, new BlockPos(x, y, z), bodyAt(feetY))));
        }
        // 与原版给这类状态缓存碰撞箱的是同一次调用:不看世界、不看身体
        return CACHE.computeIfAbsent(state,
                s -> classify(split(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
    }

    /** 从碰撞箱推出它是哪一类:只有一个且正好是整格的算整块。 */
    private static Shape classify(AABB[] boxes) {
        if (boxes.length == 0) {
            return EMPTY;
        }
        if (boxes.length == 1 && boxes[0].equals(FULL[0])) {
            return WHOLE;
        }
        return new Shape(boxes, Fill.PARTIAL);
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
