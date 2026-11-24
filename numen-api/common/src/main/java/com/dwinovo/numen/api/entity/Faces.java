package com.dwinovo.numen.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 面:点一格方块时点它的哪一面、落在面上哪一点,往一格里放方块时贴着哪个邻格的哪一面。都是纯几何,不问看不看得见——她点一格只看
 * 够不够得着({@link Mouse#reaches(BlockPos)})。
 *
 * <p>贴面照原版:有轮廓的邻格({@code getShape},不是碰撞箱)都能贴,所以台阶、楼梯、栅栏、活板门、玻璃板、火把……只要有轮廓就有面
 * 可点,流体、空气没有。点中的邻格自己若可以被顶替(高草、单层雪),方块会落进那一格而不是要放的格,那一面就不算。放下去的方块
 * 要不要支撑、会不会立刻掉下来,由原版放置规则决定,不在这里判。
 */
public final class Faces {

    private Faces() {}

    /** 放方块时点的那一下:点 {@code clicked} 这一格的 {@code side} 面。 */
    public record Face(BlockPos clicked, Direction side) {}

    /** 六个方向,固定一份:{@link Direction#values} 每次调用都复制整个数组。朝下的排在最前。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * {@code pos} 这一格朝着 {@code eye} 的那一面:站在那儿的人会点的那一面。眼睛在哪几面的外侧就在这几面里挑,面前那一格不是整块
     * (这一面露着)的优先,同样露着取最正对着眼睛的(眼睛离那一面所在的平面最远);眼睛就在这一格里时取离眼睛最近的那一面。
     * 没指名点哪一面的右键与左键都问这里,只此一处。
     */
    public static Direction toward(BlockGetter level, Vec3 eye, BlockPos pos) {
        Direction best = null;
        boolean bestOpen = false;
        double bestBeyond = Double.NEGATIVE_INFINITY;
        BlockPos.MutableBlockPos front = new BlockPos.MutableBlockPos();
        for (Direction side : DIRECTIONS) {
            // 眼睛在这一面所在的平面外侧多远;不在外侧为负
            double beyond = (eye.x - pos.getX() - 0.5) * side.getStepX() + (eye.y - pos.getY() - 0.5) * side.getStepY()
                    + (eye.z - pos.getZ() - 0.5) * side.getStepZ() - 0.5;
            boolean open = beyond > 0 && !Block.isShapeFullBlock(
                    level.getBlockState(front.setWithOffset(pos, side)).getShape(level, front));
            if (best == null || (open && !bestOpen) || (open == bestOpen && beyond > bestBeyond)) {
                best = side;
                bestOpen = open;
                bestBeyond = beyond;
            }
        }
        return best;
    }

    /**
     * 往 {@code target} 放 {@code placing} 时贴着点的那一面:能点的邻格(有轮廓、不会被 {@code placing} 顶替)朝着 {@code target}
     * 的那一面,先下面那一格的顶面,其余照方向的先后;一个能点的邻格也没有为 null。{@code placing} 为 null 是"随便一块方块"。
     */
    public static Face against(BlockGetter level, BlockPos target, Block placing) {
        Direction dir = neighbor(level, target, placing);
        return dir == null ? null : new Face(target.relative(dir), dir.getOpposite());
    }

    /** {@link #against} 是不是交得出一面。只问"有没有面可贴"的地方用它:搜索里每个垫、搭的候选都问,不为它建坐标。 */
    public static boolean anyAgainst(BlockGetter level, BlockPos target, Block placing) {
        return neighbor(level, target, placing) != null;
    }

    /** 从 {@code target} 看过去,第一个能点的邻格在哪个方向;没有为 null。 */
    private static Direction neighbor(BlockGetter level, BlockPos target, Block placing) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (Direction dir : DIRECTIONS) {
            BlockState state = level.getBlockState(neighbor.setWithOffset(target, dir));
            if (!state.getShape(level, neighbor).isEmpty() && !Replaceable.replaceableBy(state, placing)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * 点 {@code block} 这一格的 {@code face} 面时落在的那一点(绝对坐标):轮廓在这个方向上的最外一层里面积最大的那块面的中心。
     * 下半砖的顶面在 0.5 高,就点在 0.5 高。没有轮廓的格(空气、流体)按整格算,落在那一面的正中。
     */
    public static Vec3 point(BlockGetter level, BlockPos block, Direction face) {
        VoxelShape shape = level.getBlockState(block).getShape(level, block);
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        if (shape.isEmpty() || shape == Shapes.block()) {
            // 整块的轮廓就是单位方块,面心在 (面所在的一格边界, 0.5, 0.5);不为它列盒子
            double flat = positive ? 1.0 : 0.0;
            return switch (axis) {
                case X -> new Vec3(flat + block.getX(), 0.5 + block.getY(), 0.5 + block.getZ());
                case Y -> new Vec3(0.5 + block.getX(), flat + block.getY(), 0.5 + block.getZ());
                case Z -> new Vec3(0.5 + block.getX(), 0.5 + block.getY(), flat + block.getZ());
            };
        }
        double plane = positive ? shape.max(axis) : shape.min(axis);
        AABB best = null;
        double bestArea = -1;
        for (AABB box : shape.toAabbs()) {
            double edge = positive ? box.max(axis) : box.min(axis);
            if (edge != plane) {
                continue;
            }
            double area = faceArea(box, axis);
            if (area > bestArea) {
                best = box;
                bestArea = area;
            }
        }
        Vec3 center = best.getCenter();
        Vec3 onFace = switch (axis) {
            case X -> new Vec3(plane, center.y, center.z);
            case Y -> new Vec3(center.x, plane, center.z);
            case Z -> new Vec3(center.x, center.y, plane);
        };
        return onFace.add(block.getX(), block.getY(), block.getZ());
    }

    private static double faceArea(AABB box, Direction.Axis axis) {
        return switch (axis) {
            case X -> box.getYsize() * box.getZsize();
            case Y -> box.getXsize() * box.getZsize();
            case Z -> box.getXsize() * box.getYsize();
        };
    }
}
