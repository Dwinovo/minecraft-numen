package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 视线:从眼睛到一格方块、到一点,中间隔着什么。"看不看得见"只在这里判,答的是感知(插件里"看得见吗"的那个函数);她点一格、挖一格
 * 不问它,只看够不够得着({@link Mouse#reaches(BlockPos)})。
 *
 * <p>口径照原版准星拾取({@code BlockGetter.clip},{@code ClipContext.Block.OUTLINE},不看流体):沿射线逐格,按方块轮廓
 * (连同交互轮廓的覆写)算碰没碰上。与原版的 clip 只差一处:碰上别的格之后不停,一直数到目标,把挡着的格分成两种——
 * <ul>
 *   <li><b>软遮挡</b>({@link #soft}):往里放一块别种方块时原版会直接顶掉的格({@link Replaceable#displaced}:草、高草、
 *       蕨、枯灌木、单层雪、没长满的藤蔓……),一下就掉;</li>
 *   <li><b>硬遮挡</b>:其余有轮廓的格,要挖开才看得见。</li>
 * </ul>
 */
public final class Sight {

    /** 瞄面上的点时往面里收这么多:射线不贴着棱,从面朝外那一侧射过来才碰得上这一面。 */
    private static final double INSET = 0.02;
    /** 射线打到瞄点之后再往前伸这么多,保证穿进那一面。 */
    private static final double THROUGH = 0.1;

    /** {@link Direction#values} 每次调用都复制一份数组。 */
    private static final Direction[] SIDES = Direction.values();

    private Sight() {}

    /**
     * 一次视线:从眼睛朝一点射过去的结果。
     *
     * @param reached 射线碰上了目标那一格的轮廓
     * @param hard    路上挡着的硬遮挡,按先后
     * @param soft    路上挡着的软遮挡,按先后
     */
    public record Trace(boolean reached, List<BlockPos> hard, List<BlockPos> soft) {

        /** 第一下就碰上目标,路上什么也没隔着。 */
        public boolean clear() {
            return reached && hard.isEmpty() && soft.isEmpty();
        }
    }

    /** 软遮挡:挡在视线上、一下就掉的格。判据只在这里:往里放一块别种方块时原版会直接顶掉它。 */
    public static boolean soft(BlockState state) {
        return Replaceable.displaced(state);
    }

    /**
     * 从 {@code eye} 朝 {@code point} 射一条视线,数到 {@code target}(可以为 null:不认目标,数到那一点为止)为止:碰没碰上它、
     * 路上隔着哪些格。射线打到瞄点再往前伸一点,穿进那一面。
     */
    public static Trace trace(BlockGetter level, Vec3 eye, Vec3 point, BlockPos target) {
        Line line = new Line();
        line.shoot(level, eye, point.x, point.y, point.z, target);
        return line.trace();
    }

    /** {@code target} 朝着 {@code eye} 的各面上的瞄点({@link Faces#point} 往面里收 {@link #INSET})。 */
    public static List<Vec3> faces(BlockGetter level, Vec3 eye, BlockPos target) {
        List<Vec3> out = new ArrayList<>(3);
        for (Direction side : SIDES) {
            Vec3 onFace = Faces.point(level, target, side);
            if (facing(eye.x, eye.y, eye.z, onFace.x, onFace.y, onFace.z, side)) {
                out.add(inset(onFace, side));
            }
        }
        return out;
    }

    /** 一条视线的追踪:数到目标为止,路上碰着轮廓的格分成硬、软两种。 */
    private static final class Line {

        /** 逐格的回调不带状态(状态都在 {@code Line} 上),不随每条线新建。 */
        private static final BiFunction<Line, BlockPos, Direction> VISIT = Line::visit;
        private static final Function<Line, Direction> MISS = line -> null;

        private BlockGetter level;
        private Vec3 eye;
        private Vec3 through;
        private BlockPos target;
        private boolean reached;
        private List<BlockPos> hard = List.of();
        private List<BlockPos> soft = List.of();

        /** 从 {@code eye} 朝 {@code (px, py, pz)} 射一条线,伸过瞄点 {@link #THROUGH},数到 {@code target}(可以为 null:不认目标)为止。 */
        void shoot(BlockGetter level, Vec3 eye, double px, double py, double pz, BlockPos target) {
            this.level = level;
            this.eye = eye;
            this.target = target;
            this.hard = List.of();
            this.soft = List.of();
            // 沿眼睛到瞄点的方向再伸一点;两点近到长度不足 1e-5 时没有方向,不伸
            double dx = px - eye.x;
            double dy = py - eye.y;
            double dz = pz - eye.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double ux = 0;
            double uy = 0;
            double uz = 0;
            if (!(length < (double) 1.0E-5F)) {
                ux = dx / length;
                uy = dy / length;
                uz = dz / length;
            }
            this.through = new Vec3(px + ux * THROUGH, py + uy * THROUGH, pz + uz * THROUGH);
            Direction hit = BlockGetter.traverseBlocks(eye, through, this, VISIT, MISS);
            this.reached = hit != null;
        }

        /** 射线碰上这一格时碰上的是哪一面(碰上目标就收线,交回这一面);没碰上、或碰上的是路上的遮挡,交回 null。 */
        private Direction visit(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getShape(level, pos);
            boolean isTarget = pos.equals(target);
            Direction on;
            if (shape == Shapes.block() && (!isTarget || state.getInteractionShape(level, pos).isEmpty())) {
                // 整块:自己算碰没碰上、碰哪一面,不为它建原版 clip 要的点、盒、坐标表
                on = clipUnit(pos.getX(), pos.getY(), pos.getZ());
            } else {
                BlockHitResult hit = level.clipWithInteractionOverride(eye, through, pos, shape, state);
                on = hit == null ? null : hit.getDirection();
            }
            if (on == null) {
                return null;
            }
            if (isTarget) {
                return on;
            }
            if (Sight.soft(state)) {
                if (soft.isEmpty()) {
                    soft = new ArrayList<>(2);
                }
                soft.add(pos.immutable());
            } else {
                if (hard.isEmpty()) {
                    hard = new ArrayList<>(2);
                }
                hard.add(pos.immutable());
            }
            return null;
        }

        /**
         * 射线({@link #eye} 到 {@link #through})碰上 {@code (px, py, pz)} 那一整格的哪一面,没碰上为 null;口径逐步照
         * {@code VoxelShape.clip} 对整块的做法:射线短到没有方向(长度平方不到 1e-7)不算碰上;起点往前挪千分之一在格里,射线从格里出发,
         * 碰上的是射线朝向的反面;否则照 {@code AABB.clip} 看射线进格的那三个面,交点落在面内(各边外放 1e-7)、离起点最近的那一个。
         */
        private Direction clipUnit(int px, int py, int pz) {
            double dx = through.x - eye.x;
            double dy = through.y - eye.y;
            double dz = through.z - eye.z;
            if (dx * dx + dy * dy + dz * dz < 1.0E-7) {
                return null;
            }
            if (within(eye.x + dx * 0.001 - px) && within(eye.y + dy * 0.001 - py) && within(eye.z + dz * 0.001 - pz)) {
                return Direction.getNearest(dx, dy, dz).getOpposite();
            }
            Direction face = null;
            double nearest = 1.0;
            if (dx > 1.0E-7) {
                double t = along(px, eye.x, dx, eye.y, dy, py, eye.z, dz, pz, nearest);
                if (t < nearest) {
                    nearest = t;
                    face = Direction.WEST;
                }
            } else if (dx < -1.0E-7) {
                double t = along(px + 1.0, eye.x, dx, eye.y, dy, py, eye.z, dz, pz, nearest);
                if (t < nearest) {
                    nearest = t;
                    face = Direction.EAST;
                }
            }
            if (dy > 1.0E-7) {
                double t = along(py, eye.y, dy, eye.z, dz, pz, eye.x, dx, px, nearest);
                if (t < nearest) {
                    nearest = t;
                    face = Direction.DOWN;
                }
            } else if (dy < -1.0E-7) {
                double t = along(py + 1.0, eye.y, dy, eye.z, dz, pz, eye.x, dx, px, nearest);
                if (t < nearest) {
                    nearest = t;
                    face = Direction.UP;
                }
            }
            if (dz > 1.0E-7) {
                double t = along(pz, eye.z, dz, eye.x, dx, px, eye.y, dy, py, nearest);
                if (t < nearest) {
                    nearest = t;
                    face = Direction.NORTH;
                }
            } else if (dz < -1.0E-7) {
                double t = along(pz + 1.0, eye.z, dz, eye.x, dx, px, eye.y, dy, py, nearest);
                if (t < nearest) {
                    face = Direction.SOUTH;
                }
            }
            return face;
        }

        /** 格内坐标在 [0, 1) 里:整块轮廓的体素只有一格。 */
        private static boolean within(double local) {
            return Mth.floor(local) == 0;
        }

        /**
         * 射线穿过垂直于某根轴、在 {@code plane} 的那个面时的位置(0 到 1 之间的比例):比 {@code nearest} 近、交点落在这一格那个面的
         * 范围里(两个另外的轴各是 {@code [min, min + 1]},外放 1e-7)才算,否则交回 {@code nearest}。
         */
        private static double along(double plane, double start, double delta, double startA, double deltaA, double minA,
                                    double startB, double deltaB, double minB, double nearest) {
            double t = (plane - start) / delta;
            double a = startA + t * deltaA;
            double b = startB + t * deltaB;
            return 0.0 < t && t < nearest && minA - 1.0E-7 < a && a < minA + 1.0 + 1.0E-7
                    && minB - 1.0E-7 < b && b < minB + 1.0 + 1.0E-7 ? t : nearest;
        }

        /** 这一条线的结果。 */
        Trace trace() {
            return new Trace(reached, List.copyOf(hard), List.copyOf(soft));
        }
    }

    /** 面上的点往面里收 {@link #INSET}。 */
    private static Vec3 inset(Vec3 onFace, Direction side) {
        return new Vec3(onFace.x - side.getStepX() * INSET, onFace.y - side.getStepY() * INSET,
                onFace.z - side.getStepZ() * INSET);
    }

    /** 眼睛在这个面朝外的一侧:从这里看得到这一面。 */
    private static boolean facing(double ex, double ey, double ez, double px, double py, double pz, Direction side) {
        return (ex - px) * side.getStepX() + (ey - py) * side.getStepY() + (ez - pz) * side.getStepZ() > 1.0E-4;
    }

    /** 这一格有没有轮廓:空气、流体没有,那里没有东西可点、可挖,也挡不住视线。 */
    public static boolean clickable(BlockGetter level, BlockPos pos) {
        return !level.getBlockState(pos).getShape(level, pos).isEmpty();
    }
}
