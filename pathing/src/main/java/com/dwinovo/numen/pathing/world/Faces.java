package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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

    /** 放方块时点的那一下:点 {@code clicked} 这一格的 {@code side} 面上的 {@code point}。 */
    public record Face(BlockPos clicked, Direction side, Vec3 point) {}

    /**
     * 眼睛在 {@code eye}、交互距离 {@code range} 时,往 {@code target} 放 {@code placing} 点得中的那个面:能贴的面由
     * {@link #against} 给,点在 {@link #hitPoint};眼睛要在那个面朝外的一侧,射线({@link Sight})第一下碰上它,够得着,放下去
     * 落在 {@code target}。一个都点不中为 null。优先点下面那一格的顶面。规划判"站在这里放得下"与执行时瞄哪一点问的都是它。
     */
    public static Face inSight(BlockGetter level, Vec3 eye, double range, BlockPos target, Block placing) {
        List<Direction> sides = new ArrayList<>(against(level, target, placing));
        sides.sort((a, b) -> Boolean.compare(b == Direction.DOWN, a == Direction.DOWN));
        for (Direction dir : sides) {
            BlockPos clicked = target.relative(dir);
            Direction side = dir.getOpposite();
            Vec3 onFace = hitPoint(level, target, dir);
            Vec3 point = Sight.inset(onFace, side);
            if (!Sight.facing(eye, onFace, side) || onFace.distanceTo(eye) >= range) {
                continue;
            }
            if (!target.equals(Replaceable.landing(level, clicked, side, placing))) {
                continue;
            }
            if (Sight.trace(level, eye, point, clicked).clear(side)) {
                return new Face(clicked, side, point);
            }
        }
        return null;
    }

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
        return point(level, target.relative(dir), dir.getOpposite());
    }

    /**
     * 点 {@code block} 这一格的 {@code face} 面时准星该落的点(绝对坐标):轮廓在这个方向上的最外一层里面积最大的那块面的中心。
     * 放方块时点邻格的那一面、用一格方块时点它自己的一面,都从这里取。
     *
     * @throws IllegalArgumentException 那一格没有轮廓,点不中
     */
    public static Vec3 point(BlockGetter level, BlockPos block, Direction face) {
        VoxelShape shape = level.getBlockState(block).getShape(level, block);
        if (shape.isEmpty()) {
            throw new IllegalArgumentException(block + " 没有轮廓,点不中");
        }
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
