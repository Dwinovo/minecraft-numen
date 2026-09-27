package com.dwinovo.numen.pathing.world;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 贴面:往一格里放方块时,可以点哪些邻格的哪一面。照原版,点得中的面都能贴——准星点中的是方块的轮廓
 * ({@code getShape},不是碰撞箱),所以台阶、楼梯、栅栏、活板门、玻璃板、火把……只要有轮廓就有面可点,流体、空气没有。
 * 点中的邻格自己若可以被顶替(高草、单层雪),方块会落进那一格而不是要放的格,那一面就不算。放下去的方块要不要支撑、
 * 会不会立刻掉下来,由原版放置规则决定,不在这里判。
 *
 * <p>点在哪:面朝要放的格那一侧,轮廓在这个方向上的最外一层里面积最大的那块面的中心。下半砖的顶面在 0.5 高,
 * 就点在 0.5 高;规划与执行都从这里取这个点。
 */
public final class Faces {

    private Faces() {}

    /**
     * 往 {@code target} 放 {@code placing} 时,可以点的邻格所在的方向(从 {@code target} 看过去)。点的是那个邻格朝向
     * {@code target} 的那一面,即方向的反向。
     */
    public static Set<Direction> against(BlockGetter level, BlockPos target, Block placing) {
        EnumSet<Direction> out = EnumSet.noneOf(Direction.class);
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.relative(dir);
            BlockState state = level.getBlockState(neighbor);
            if (!state.getShape(level, neighbor).isEmpty() && !Replaceable.replaceableBy(state, placing)) {
                out.add(dir);
            }
        }
        return out;
    }

    /**
     * 要往 {@code target} 放、点 {@code dir} 方向那个邻格时,准星该落的点(绝对坐标)。
     *
     * @throws IllegalArgumentException 那个邻格没有轮廓,点不中
     */
    public static Vec3 hitPoint(BlockGetter level, BlockPos target, Direction dir) {
        BlockPos neighbor = target.relative(dir);
        VoxelShape shape = level.getBlockState(neighbor).getShape(level, neighbor);
        if (shape.isEmpty()) {
            throw new IllegalArgumentException(neighbor + " 没有轮廓,点不中");
        }
        Direction face = dir.getOpposite();
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
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
        return onFace.add(neighbor.getX(), neighbor.getY(), neighbor.getZ());
    }

    private static double faceArea(AABB box, Direction.Axis axis) {
        return switch (axis) {
            case X -> box.getYsize() * box.getZsize();
            case Y -> box.getXsize() * box.getZsize();
            case Z -> box.getXsize() * box.getYsize();
        };
    }
}
