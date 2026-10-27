package com.dwinovo.numen.api.entity;

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

    /** 按节点中心算站位时给交互距离留的余量:身体停在节点里不一定正在中心。 */
    private static final double STANDING_SLACK = 0.4;

    /** 面上除中心与离眼睛最近的一点之外再试的几处点:在这一面的两个方向上各取四分之一与四分之三。 */
    private static final double[] QUARTERS = {0.25, 0.75};

    /**
     * 眼睛在 {@code eye}、交互距离 {@code range} 时,往 {@code target} 放 {@code placing} 点得中的那个面:第一个看得见、够得着、
     * 放下去落在 {@code target} 的({@link #inSightAll}),先试下面那一格的顶面;一个都点不中为 null。规划判"站在这里放得下"
     * 与执行时瞄哪一点问的都是它。
     */
    public static Face inSight(BlockGetter level, Vec3 eye, double range, BlockPos target, Block placing) {
        for (Face face : inSightAll(level, eye, range, target, placing)) {
            if (target.equals(Replaceable.landing(level, face.clicked(), face.side(), placing))) {
                return face;
            }
        }
        return null;
    }

    /**
     * 往 {@code target} 放方块时可以点的点击,从 {@code eye} 看出去先后排好:能贴的面({@link #against}),先下面那一格的顶面;
     * 格内本身是可顶替的方块时再加上这一格自己的六个面(原地点,与原版一样)。每个面取轮廓里最大那块的中心、面上离眼睛最近的一点
     * (够得着一格量的是眼睛到这一格最近的一点,面中心可能已经远出一截)、再四个象限点(台阶上下半、门的合页左右由点在面上的位置定)。
     * 点是瞄点,往面里收了一点({@link Sight#inset});不管看不看得见、够不够得着。{@code placing} 为 null 是"随便一块方块"。
     */
    public static List<Face> candidates(BlockGetter level, BlockPos target, Block placing, Vec3 eye) {
        List<Face> out = new ArrayList<>();
        List<Direction> around = new ArrayList<>(against(level, target, placing));
        around.sort((a, b) -> Boolean.compare(b == Direction.DOWN, a == Direction.DOWN));
        for (Direction dir : around) {
            addFace(out, level, target.relative(dir), dir.getOpposite(), eye);
        }
        if (Sight.clickable(level, target) && Replaceable.replaceableBy(level.getBlockState(target), placing)) {
            for (Direction side : Direction.values()) {
                addFace(out, level, target, side, eye);
            }
        }
        return out;
    }

    /**
     * {@link #candidates} 里从 {@code eye} 看得见、够得着的那些,先后不变:眼睛在面朝外的一侧,面上的点在交互距离 {@code range}
     * 以内,射线({@link Sight})第一下碰上的就是这一面。
     */
    public static List<Face> inSightAll(BlockGetter level, Vec3 eye, double range, BlockPos target, Block placing) {
        List<Face> out = new ArrayList<>();
        for (Face face : candidates(level, target, placing, eye)) {
            if (inSight(level, eye, range, face)) {
                out.add(face);
            }
        }
        return out;
    }

    /**
     * 站在 {@code eye} 这个位置,往 {@code target} 这一格放方块,手点不点得着——"够得着"在整个仓库里只有这一个判据:站位选择
     * ({@code Goals.place} 的停点)、施工数格({@code BuildSurvey})、手放挑点击({@code BuildPlacer})问的都是它。
     *
     * <p>按这一格里的东西已经清掉了算(创造模式放之前先碎,生存的要挖的格另有说法),所以挡在眼睛和邻格之间的是这一格自己的话不算挡。
     * 没有任何能点的面(悬空)手点不了,放的一方直接写进去,站在哪儿都一样:算够得着,够不够得着只看眼睛到这一格的距离({@code Reach})。
     */
    public static boolean handReachable(BlockGetter level, Vec3 eye, double range, BlockPos target) {
        BlockGetter cleared = new Cleared(level, target);
        List<Face> all = candidates(cleared, target, null, eye);
        if (all.isEmpty()) {
            return true;
        }
        // 站位是节点中心站直的眼睛,真停下来离中心差一点:留一点余量,停下之后手放仍点得着
        double standing = range - STANDING_SLACK;
        for (Face face : all) {
            if (inSight(cleared, eye, standing, face)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inSight(BlockGetter level, Vec3 eye, double range, Face face) {
        Vec3 onFace = outset(face.point(), face.side());
        return Sight.facing(eye, onFace, face.side()) && onFace.distanceTo(eye) < range
                && Sight.trace(level, eye, face.point(), face.clicked()).clear(face.side());
    }

    private static Vec3 outset(Vec3 point, Direction side) {
        return new Vec3(point.x + side.getStepX() * Sight.INSET, point.y + side.getStepY() * Sight.INSET,
                point.z + side.getStepZ() * Sight.INSET);
    }

    private static void addFace(List<Face> out, BlockGetter level, BlockPos clicked, Direction side, Vec3 eye) {
        Vec3 centre = point(level, clicked, side);
        out.add(new Face(clicked, side, Sight.inset(centre, side)));
        Direction.Axis plane = side.getAxis();
        out.add(new Face(clicked, side, Sight.inset(across(centre, clicked, plane,
                nearestAlong(eye, clicked, plane, true), nearestAlong(eye, clicked, plane, false)), side)));
        for (double u : QUARTERS) {
            for (double v : QUARTERS) {
                out.add(new Face(clicked, side, Sight.inset(across(centre, clicked, plane, u, v), side)));
            }
        }
    }

    /** 面所在的平面不变,面内两个方向上落在这一格的 {@code u}、{@code v} 处。 */
    private static Vec3 across(Vec3 onFace, BlockPos block, Direction.Axis plane, double u, double v) {
        return switch (plane) {
            case X -> new Vec3(onFace.x, block.getY() + u, block.getZ() + v);
            case Y -> new Vec3(block.getX() + u, onFace.y, block.getZ() + v);
            case Z -> new Vec3(block.getX() + u, block.getY() + v, onFace.z);
        };
    }

    /** 眼睛在面内两个方向上的投影夹进这一格(离棱 0.05);{@code first} 取面内的第一个方向,否则取第二个。 */
    private static double nearestAlong(Vec3 eye, BlockPos block, Direction.Axis plane, boolean first) {
        double along = switch (plane) {
            case X -> first ? eye.y - block.getY() : eye.z - block.getZ();
            case Y -> first ? eye.x - block.getX() : eye.z - block.getZ();
            case Z -> first ? eye.x - block.getX() : eye.y - block.getY();
        };
        return Math.max(0.05, Math.min(0.95, along));
    }

    /** {@code level} 的样子,只有 {@code cleared} 那一格当空气:这一格里的东西放之前会先清掉。 */
    private record Cleared(BlockGetter level, BlockPos cleared) implements BlockGetter {

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return pos.equals(cleared) ? null : level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(cleared) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : level.getBlockState(pos);
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return pos.equals(cleared) ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                    : level.getFluidState(pos);
        }

        /** Paper 给 {@code BlockGetter} 加了两个抽象方法(只读已加载的),不实现就编不过;原版上它们只是两个无关的方法。 */
        public BlockState getBlockStateIfLoaded(BlockPos pos) {
            return getBlockState(pos);
        }

        public net.minecraft.world.level.material.FluidState getFluidIfLoaded(BlockPos pos) {
            return getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
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
