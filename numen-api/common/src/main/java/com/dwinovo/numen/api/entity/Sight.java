package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 视线:从眼睛到一格方块某一面上的一点,中间隔着什么。"看不看得见那一格"只在这里判——搜索在快照上调它(用一格方块的站位、
 * 挖一格时挡着几格),执行在活世界上调它(到达复核、挖放与按键的瞄点)。
 *
 * <p>口径照原版准星拾取({@code BlockGetter.clip},{@code ClipContext.Block.OUTLINE},不看流体):沿射线逐格,按方块轮廓
 * (连同交互轮廓的覆写)算碰没碰上。与原版的 clip 只差一处:碰上别的格之后不停,一直数到目标,把挡着的格分成两种——
 * <ul>
 *   <li><b>软遮挡</b>({@link #soft}):往里放一块别种方块时原版会直接顶掉的格({@link Replaceable#displaced}:草、高草、
 *       蕨、枯灌木、单层雪、没长满的藤蔓……)。一下就掉、清掉它不算改建;清不清归用那一格的一方(挖掘执行清掉它,纯按键点到的就是它);</li>
 *   <li><b>硬遮挡</b>:其余有轮廓的格,要挖开才看得见。</li>
 * </ul>
 */
public final class Sight {

    /** 瞄面上的点时往面里收这么多:射线不贴着棱,从面朝外那一侧射过来才碰得上这一面。 */
    public static final double INSET = 0.02;
    /** 射线打到瞄点之后再往前伸这么多,保证穿进那一面。 */
    private static final double THROUGH = 0.1;

    /** {@link Direction#values} 每次调用都复制一份数组。 */
    private static final Direction[] SIDES = Direction.values();

    private Sight() {}

    /**
     * 一次视线:从眼睛朝 {@code point} 射过去的结果。
     *
     * @param point   瞄点
     * @param reached 射线碰上了目标那一格的轮廓
     * @param face    碰上的是目标的哪一面;没碰上为 null
     * @param hard    路上挡着的硬遮挡,按先后
     * @param soft    路上挡着的软遮挡,按先后
     */
    public record Trace(Vec3 point, boolean reached, Direction face, List<BlockPos> hard, List<BlockPos> soft) {

        /** 第一下就碰上目标:准星对过去就落在它上面。给了 {@code side} 时还得是那一面。 */
        public boolean clear(Direction side) {
            return clearable(side) && soft.isEmpty();
        }

        /** 只隔着软遮挡:清掉它们就看得见。给了 {@code side} 时碰上的还得是那一面。 */
        public boolean clearable(Direction side) {
            return reached && hard.isEmpty() && (side == null || face == side);
        }
    }

    /** 软遮挡:挡在视线上、用之前一下清掉就行的格。判据只在这里:往里放一块别种方块时原版会直接顶掉它。 */
    public static boolean soft(BlockState state) {
        return Replaceable.displaced(state);
    }

    /**
     * 从 {@code eye} 朝 {@code point} 射一条视线,数到 {@code target} 为止:碰没碰上它、碰上的是哪一面、路上隔着哪些格。
     * 射线打到瞄点再往前伸一点,穿进那一面。
     */
    public static Trace trace(BlockGetter level, Vec3 eye, Vec3 point, BlockPos target) {
        Line line = new Line();
        line.shoot(level, eye, point.x, point.y, point.z, target);
        return line.trace(point);
    }

    /**
     * 从 {@code eye} 挖 {@code target} 时朝哪一点看:朝 {@code points} 里的每一点各打一条视线,路上挡着的格(硬的、软的)都
     * {@code clears} 的那些里,取硬遮挡最少的那一条;一条都没有为 null——看得见它的每一处都隔着清不掉的格。挖一格时给站位定价
     * (瞄点是 {@link #faces})与挖的时候先清哪一格(瞄点是身体够得着的那几点)都按这一条挑。
     */
    public static Trace dig(BlockGetter level, Vec3 eye, BlockPos target, List<Vec3> points, Predicate<BlockPos> clears) {
        Line line = new Line();
        Trace best = null;
        for (Vec3 point : points) {
            line.shoot(level, eye, point.x, point.y, point.z, target);
            if ((best == null || line.hard().size() < best.hard().size())
                    && line.hard().stream().allMatch(clears) && line.soft().stream().allMatch(clears)) {
                best = line.trace(point);
            }
        }
        return best;
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

    /**
     * 从 {@code eye} 挖 {@code target} 时够得着(离眼睛不到 {@code range})的瞄点:它的轮廓中心,轮廓各个朝着眼睛的面的中心,
     * 这些面上离眼睛最近的一点(离棱留 {@link Reach#EDGE}),先后如此,不管中间有没有东西挡着。执行转头看哪一点
     * ({@code Look#point})与规划算挡着几格({@link #cover})用的是这同一份。
     */
    public static List<Vec3> digPoints(BlockGetter level, Vec3 eye, double range, BlockPos target) {
        Points points = reachable(level, eye, range, target);
        List<Vec3> out = new ArrayList<>(points.size);
        for (int i = 0; i < points.size; i++) {
            out.add(new Vec3(points.xyz[3 * i], points.xyz[3 * i + 1], points.xyz[3 * i + 2]));
        }
        return out;
    }

    /**
     * 从 {@code eye} 挖 {@code target} 之前要先清掉哪几格:{@link #digPoints} 里只隔着软遮挡就看得见它的那几条视线中,隔得最少的
     * 那条上的软遮挡,按先后(没有就是空表)。没有哪一条只隔软遮挡(都隔着硬遮挡,或够不着)为 null——要先挖开别的格,不是清得掉的事。
     */
    public static List<BlockPos> cover(BlockGetter level, Vec3 eye, double range, BlockPos target) {
        Points points = reachable(level, eye, range, target);
        Line line = new Line();
        List<BlockPos> best = null;
        for (int i = 0; i < points.size; i++) {
            line.shoot(level, eye, points.xyz[3 * i], points.xyz[3 * i + 1], points.xyz[3 * i + 2], target);
            if (line.clearable() && (best == null || line.soft().size() < best.size())) {
                best = List.copyOf(line.soft());
                if (best.isEmpty()) {
                    // 一格软遮挡也不隔着:后面的视线不可能比它隔得更少
                    return best;
                }
            }
        }
        return best;
    }

    /** 一串瞄点,三个数一个点,先后就是 {@link #digPoints} 交出的先后。 */
    private static final class Points {

        double[] xyz = new double[12];
        int size;

        void add(double x, double y, double z) {
            if (3 * size == xyz.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
            }
            xyz[3 * size] = x;
            xyz[3 * size + 1] = y;
            xyz[3 * size + 2] = z;
            size++;
        }
    }

    /**
     * {@link #digPoints} 的算法:全用 double 算,不建 {@code Vec3} 与 {@code AABB}(规划里每个挖的候选都问一遍)。整块的轮廓
     * (和没有轮廓的格)就是单位方块,不必为它列一遍盒子。
     */
    private static Points reachable(BlockGetter level, Vec3 eye, double range, BlockPos target) {
        double ex = eye.x;
        double ey = eye.y;
        double ez = eye.z;
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        VoxelShape shape = level.getBlockState(target).getShape(level, target);
        boolean unit = shape.isEmpty() || shape == Shapes.block();
        List<AABB> boxes = unit ? null : shape.toAabbs();
        int count = unit ? 1 : boxes.size();
        Points out = new Points();
        double[] at = new double[3];
        double[] box = new double[6];
        if (unit) {
            addWithin(out, mid(0.0, 1.0) + tx, mid(0.0, 1.0) + ty, mid(0.0, 1.0) + tz, ex, ey, ez, range);
        } else {
            AABB bounds = shape.bounds();
            addWithin(out, mid(bounds.minX, bounds.maxX) + tx, mid(bounds.minY, bounds.maxY) + ty,
                    mid(bounds.minZ, bounds.maxZ) + tz, ex, ey, ez, range);
        }
        // 先是各个面的中心(往面里收),再是各个面上离眼睛最近的一点(往面里收)
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                AABB local = unit ? null : boxes.get(i);
                box[0] = (unit ? 0.0 : local.minX) + tx;
                box[1] = (unit ? 0.0 : local.minY) + ty;
                box[2] = (unit ? 0.0 : local.minZ) + tz;
                box[3] = (unit ? 1.0 : local.maxX) + tx;
                box[4] = (unit ? 1.0 : local.maxY) + ty;
                box[5] = (unit ? 1.0 : local.maxZ) + tz;
                for (Direction side : SIDES) {
                    faceCenter(box, side, at);
                    if (facing(ex, ey, ez, at[0], at[1], at[2], side)) {
                        if (pass == 1) {
                            nearestOnFace(box, side, ex, ey, ez, at);
                        }
                        addWithin(out, at[0] - side.getStepX() * INSET, at[1] - side.getStepY() * INSET,
                                at[2] - side.getStepZ() * INSET, ex, ey, ez, range);
                    }
                }
            }
        }
        return out;
    }

    /** 区间的中点,同 {@code AABB.getCenter} 的算法。 */
    private static double mid(double min, double max) {
        return min + 0.5 * (max - min);
    }

    /** 点离眼睛不到 {@code range} 才算。 */
    private static void addWithin(Points out, double x, double y, double z, double ex, double ey, double ez, double range) {
        double dx = ex - x;
        double dy = ey - y;
        double dz = ez - z;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) < range) {
            out.add(x, y, z);
        }
    }

    /** {@code box}({@code minX, minY, minZ, maxX, maxY, maxZ})的 {@code side} 面的中心。 */
    private static void faceCenter(double[] box, Direction side, double[] out) {
        double cx = mid(box[0], box[3]);
        double cy = mid(box[1], box[4]);
        double cz = mid(box[2], box[5]);
        switch (side) {
            case DOWN -> set(out, cx, box[1], cz);
            case UP -> set(out, cx, box[4], cz);
            case NORTH -> set(out, cx, cy, box[2]);
            case SOUTH -> set(out, cx, cy, box[5]);
            case WEST -> set(out, box[0], cy, cz);
            case EAST -> set(out, box[3], cy, cz);
        }
    }

    /** {@code box} 的 {@code side} 面上离眼睛最近的一点,离面的四条棱各留 {@link Reach#EDGE}。 */
    private static void nearestOnFace(double[] box, Direction side, double ex, double ey, double ez, double[] out) {
        double x = clampInside(ex, box[0], box[3]);
        double y = clampInside(ey, box[1], box[4]);
        double z = clampInside(ez, box[2], box[5]);
        switch (side) {
            case DOWN -> set(out, x, box[1], z);
            case UP -> set(out, x, box[4], z);
            case NORTH -> set(out, x, y, box[2]);
            case SOUTH -> set(out, x, y, box[5]);
            case WEST -> set(out, box[0], y, z);
            case EAST -> set(out, box[3], y, z);
        }
    }

    private static void set(double[] out, double x, double y, double z) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
    }

    static double clampInside(double v, double min, double max) {
        double margin = Math.min(Reach.EDGE, (max - min) / 2);
        return Mth.clamp(v, min + margin, max - margin);
    }

    /**
     * 一条视线的追踪:数到目标为止,路上碰着轮廓的格分成硬、软两种。{@link #cover}、{@link #dig} 对着一串瞄点一条一条射,
     * 同一个对象反复 {@link #shoot},不为每条线建一份表与回调。
     */
    private static final class Line {

        /** 逐格的回调不带状态(状态都在 {@code Line} 上),不随每条线新建。 */
        private static final BiFunction<Line, BlockPos, Direction> VISIT = Line::visit;
        private static final Function<Line, Direction> MISS = line -> null;

        private BlockGetter level;
        private Vec3 eye;
        private Vec3 through;
        private BlockPos target;
        private boolean reached;
        private Direction face;
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
            this.face = hit;
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

        /** 碰上了目标,路上没有硬遮挡。 */
        boolean clearable() {
            return reached && hard.isEmpty();
        }

        /** 路上的硬遮挡,按先后;下一次 {@link #shoot} 之前有效。 */
        List<BlockPos> hard() {
            return hard;
        }

        /** 路上的软遮挡,按先后;下一次 {@link #shoot} 之前有效。 */
        List<BlockPos> soft() {
            return soft;
        }

        /** 这一条线的结果,瞄点记作 {@code point}。 */
        Trace trace(Vec3 point) {
            return new Trace(point, reached, face, List.copyOf(hard), List.copyOf(soft));
        }
    }

    /** 用一格方块时点它 {@code side} 面的那一点:面上的瞄点({@link Faces#point})往面里收 {@link #INSET}。 */
    public static Vec3 aim(BlockGetter level, BlockPos target, Direction side) {
        return inset(Faces.point(level, target, side), side);
    }

    /**
     * 点一只实体时瞄它身上的哪一点:包围盒上离眼睛最近的那一点,往盒心收 {@link #INSET}。够不够得着
     * ({@code Player.canInteractWithEntity})量的是眼睛到包围盒的距离,准星落在这一点上的距离就是那个数——判据说够得着,
     * 准星就点得中。瞄它的眼睛不行:宽而矮的实体(船)眼睛在盒子中间,比盒边远,够得着却落不上准星。
     */
    public static Vec3 aim(Vec3 eye, AABB box) {
        Vec3 near = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        Vec3 inward = box.getCenter().subtract(near);
        double length = inward.length();
        return length <= INSET ? near : near.add(inward.scale(INSET / length));
    }

    /** 面上的点往面里收 {@link #INSET}。 */
    public static Vec3 inset(Vec3 onFace, Direction side) {
        return new Vec3(onFace.x - side.getStepX() * INSET, onFace.y - side.getStepY() * INSET,
                onFace.z - side.getStepZ() * INSET);
    }

    /** 眼睛在这个面朝外的一侧:从这里看得到这一面。 */
    public static boolean facing(Vec3 eye, Vec3 onFace, Direction side) {
        return facing(eye.x, eye.y, eye.z, onFace.x, onFace.y, onFace.z, side);
    }

    private static boolean facing(double ex, double ey, double ez, double px, double py, double pz, Direction side) {
        return (ex - px) * side.getStepX() + (ey - py) * side.getStepY() + (ez - pz) * side.getStepZ() > 1.0E-4;
    }

    /** 这一格有没有可点的轮廓:空气、流体没有,点不中。 */
    public static boolean clickable(BlockGetter level, BlockPos pos) {
        return !level.getBlockState(pos).getShape(level, pos).isEmpty();
    }

    /**
     * {@code target} 的 {@code side} 面敞不敞开:面前那一格不是整块的硬遮挡,视线过得去。整块的硬方块贴着的面从哪儿都看不见,
     * 不必再打射线。
     */
    public static boolean open(BlockGetter level, BlockPos target, Direction side) {
        BlockPos front = target.relative(side);
        BlockState state = level.getBlockState(front);
        return soft(state) || !Block.isShapeFullBlock(state.getShape(level, front));
    }

    /**
     * 从 {@code eye} 用 {@code target} 的 {@code side} 面:这一面敞开({@link #open})、面朝着眼睛、面上的点在交互距离
     * {@code reach} 以内、视线上没有硬遮挡、碰上的就是这一面——这样交出这一次的视线(可能还隔着软遮挡);否则为 null。
     * 面前贴着整块硬方块的面不算,哪怕从一道细缝里斜着看得到它:那样的线差半个鼠标像素就落到别的方块上。
     */
    public static Trace use(BlockGetter level, Vec3 eye, double reach, BlockPos target, Direction side) {
        if (!open(level, target, side)) {
            return null;
        }
        Vec3 onFace = Faces.point(level, target, side);
        if (!facing(eye, onFace, side) || onFace.distanceTo(eye) >= reach) {
            return null;
        }
        Trace trace = trace(level, eye, inset(onFace, side), target);
        return trace.clearable(side) ? trace : null;
    }
}
