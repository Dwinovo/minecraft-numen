package com.dwinovo.numen.pathing.world;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 落脚:身体站在一列上时,脚落在多高、踩的是哪一格。全部从碰撞箱推导,没有按方块种类写的高度表。
 *
 * <h2>节点就是脚所在的那一格</h2>
 * 寻路的一个节点 {@code (x, y, z)} 指身体站在 {@code (x, z)} 这一列、脚的高度落在 {@code [y, y+1)} 里。脚的高度归到哪一格
 * 只有一条规则 {@link #cellOf}:规划的节点、执行时"人现在在哪个节点"都用它。站在灵魂沙(顶面 0.875)、耕地与土径(0.9375)、
 * 下半砖(0.5)、地毯、雪层上时,脚就在那块方块自己的格里;站在整块、楼梯、上半砖、栅栏(1.5)上时,脚在上面一格。
 *
 * <h2>脚落在多高</h2>
 * 身体在列中心,脚底是宽 {@link BodyStats#width()} 的正方形。脚下能托住它的,是与这块脚底有交叠的碰撞箱里顶面最高的那个;
 * 对节点 {@code y} 来说,只数顶面归在第 {@code y} 格的那些——来自这一格本身(顶面低于 1 的矮方块),或来自下面一格
 * (顶面正好 1 的整块、楼梯,顶面 1.5 的栅栏、墙)。楼梯格里的下半层顶面 0.5 也会被数进来,但那里的上半层卡着身体,
 * 由 {@link Clearance} 否掉;所以落脚只回答"托不托得住、托在多高",站不站得进去是净空的事。
 */
public final class Footing {

    /**
     * 脚的高度离上面一格的底不到这么多,就算进上面一格。吸收碰撞结算留下的浮点误差;原版判"身体在不在一个形状上面"
     * ({@code EntityCollisionContext.isAbove})用的是同一个容差。比较两个脚高是否相同、是否超过某个高度,全模块都用它。
     */
    public static final double EPSILON = 1.0E-5;
    private static final int NO_SUPPORT = Integer.MIN_VALUE;
    /** 落脚高度,一次搜索里按格记住({@link Recall})。读的格:这一列的 {@code y - 1} 与 {@code y},穿冰霜行者时再看水面上一格({@code y + 1})。 */
    private static final Recall.HeightFact HEIGHT = new Recall.HeightFact(Footing::measureHeight, new Recall.Reach(1, 1));

    private Footing() {}

    /** 脚的高度归在哪一格。全仓只此一条规则。 */
    public static int cellOf(double feetY) {
        return Mth.floor(feetY + EPSILON);
    }

    /**
     * 身体站在节点 {@code (x, y, z)} 时脚的绝对高度;这一格里没有东西托得住脚就返回 {@link Double#NaN}(脚会继续往下掉,
     * 那是下面某个节点的事)。
     */
    public static double height(BlockGetter level, BodyStats body, int x, int y, int z) {
        return HEIGHT.at(level, body, x, y, z);
    }

    private static double measureHeight(BlockGetter level, BodyStats body, int x, int y, int z) {
        double best = Double.NaN;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int cy = y - 1; cy <= y; cy++) {
            double top = highestTop(level, body, cell, x, cy, z, y);
            if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }

    /**
     * 这种方块状态每个碰撞箱的顶面,相对它所在那一格的底(整块 1,下半砖与楼梯的下半层 0.5,土径与耕地 0.9375,栅栏 1.5)。
     * 站在它上面时脚在格底之上这么高;脚所在的节点是 {@code floor(顶面)} 层,在那一层里高出 {@code 顶面 - floor(顶面)}。
     * 估价按层扫快照里出现过的状态,靠它知道每一层上一个节点最少要抬多高、下一个节点最少要落多高。碰撞箱随世界变的方块按
     * "没有世界、脚在格底"回答。
     */
    public static double[] tops(BodyStats body, BlockState state) {
        AABB[] boxes = Boxes.at(EmptyBlockGetter.INSTANCE, body, 0, 0, 0, state, 0);
        double[] tops = new double[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            tops[i] = boxes[i].maxY;
        }
        return tops;
    }

    /**
     * 身体站在节点 {@code (x, y, z)} 时踩的是哪一格:托住脚的那个碰撞箱所在的格,{@code y} 或 {@code y - 1}。
     * 踩坏耕地、触发机关、起跳系数都看它。托不住时返回 {@link Integer#MIN_VALUE}。
     */
    public static int supportY(BlockGetter level, BodyStats body, int x, int y, int z) {
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        double below = highestTop(level, body, cell, x, y - 1, z, y);
        double here = highestTop(level, body, cell, x, y, z, y);
        if (Double.isNaN(below) && Double.isNaN(here)) {
            return NO_SUPPORT;
        }
        if (Double.isNaN(below)) {
            return y;
        }
        if (Double.isNaN(here)) {
            return y - 1;
        }
        // 两格的顶面一样高时,踩的是这一格本身:身体压在它上面,原版的"脚下方块"也先看脚所在的格
        return here >= below ? y : y - 1;
    }

    /**
     * 站在节点 {@code (x, y, z)}、脚在 {@code feetY} 的身体朝 {@code (dx, dz)} 一直走到格边,脚下托着脚的碰撞箱(顶面就是 {@code feetY})
     * 是不是一直铺到那条格边:楼梯背朝空隙时,靠空隙的那半是低一级的台阶,身子走不到边就先下了一级。跑酷起跳点要踩在边上。
     */
    public static boolean reachesEdge(BlockGetter level, BodyStats body, int x, int y, int z, double feetY, int dx, int dz) {
        double half = body.width() / 2 - Clearance.DEFLATE;
        for (int cy = y - 1; cy <= y; cy++) {
            for (AABB box : Boxes.at(level, body, x, cy, z, level.getBlockState(new BlockPos(x, cy, z)), feetY)) {
                if (Math.abs(cy + box.maxY - feetY) > EPSILON
                        || box.maxX <= 0.5 - half || box.minX >= 0.5 + half || box.maxZ <= 0.5 - half || box.minZ >= 0.5 + half) {
                    continue;
                }
                boolean toEdge = dx > 0 ? box.maxX >= 1 - EPSILON : dx < 0 ? box.minX <= EPSILON
                        : dz > 0 ? box.maxZ >= 1 - EPSILON : box.minZ <= EPSILON;
                if (toEdge) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 一块托着脚的碰撞箱至少要有身宽的这么多成,才算落得上去(栅栏的立柱、铁栏杆的细柱只有一指宽,身子落上去站不住)。 */
    private static final double BROAD = 0.8;

    /**
     * 站在节点 {@code (x, y, z)}、脚在 {@code feetY} 的身体,脚下托着脚的碰撞箱里有没有一块两个水平方向都不比身宽窄多少:
     * 落点要的是一块能站上去的地方。
     */
    public static boolean broad(BlockGetter level, BodyStats body, int x, int y, int z, double feetY) {
        double half = body.width() / 2 - Clearance.DEFLATE;
        for (int cy = y - 1; cy <= y; cy++) {
            for (AABB box : Boxes.at(level, body, x, cy, z, level.getBlockState(new BlockPos(x, cy, z)), feetY)) {
                if (Math.abs(cy + box.maxY - feetY) <= EPSILON && box.maxX > 0.5 - half && box.minX < 0.5 + half
                        && box.maxZ > 0.5 - half && box.minZ < 0.5 + half
                        && box.getXsize() >= body.width() * BROAD && box.getZsize() >= body.width() * BROAD) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 身体脚底往下这么厚的一层里碰得到碰撞形状的方块,就是正托着它的。 */
    private static final double SOLE = 0.1;

    /**
     * 碰撞盒是 {@code box} 的身体此刻压在哪几格上:脚底往下一薄层里碰得到碰撞箱的那几格。撤掉其中一块之前要先挪开——
     * 挪不开就是她正站在它上面。
     */
    public static Set<BlockPos> supports(BlockGetter level, BodyStats body, AABB box) {
        AABB sole = new AABB(box.minX, box.minY - SOLE, box.minZ, box.maxX, box.minY, box.maxZ);
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(sole.minX), Mth.floor(sole.minY), Mth.floor(sole.minZ),
                Mth.floor(sole.maxX), Mth.floor(sole.maxY), Mth.floor(sole.maxZ))) {
            for (AABB piece : Boxes.at(level, body, pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos),
                    box.minY)) {
                if (piece.move(pos).intersects(sole)) {
                    out.add(pos.immutable());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 第 {@code cy} 格里与脚底交叠、顶面归在节点 {@code node} 的碰撞箱中,顶面最高的那个的绝对高度;没有则 NaN。
     * 碰撞箱随身体变化的方块按"脚正好在节点底面"回答——脚手架此时托得住,细雪托不住。
     */
    private static double highestTop(BlockGetter level, BodyStats body, BlockPos.MutableBlockPos cell, int x, int cy, int z, int node) {
        AABB[] boxes = Boxes.at(level, body, x, cy, z, level.getBlockState(cell.set(x, cy, z)), node);
        double half = body.width() / 2 - Clearance.DEFLATE;
        double best = Double.NaN;
        for (AABB box : boxes) {
            // 脚底与碰撞箱在水平面上真的有交叠(贴边不算,与原版碰撞同一个口径)
            if (box.maxX <= 0.5 - half || box.minX >= 0.5 + half || box.maxZ <= 0.5 - half || box.minZ >= 0.5 + half) {
                continue;
            }
            double top = cy + box.maxY;
            if (cellOf(top) == node && (Double.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }
}
