package com.dwinovo.numen.plugins.minecraft.pathing.search;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.plugins.minecraft.pathing.plan.ActionCosts;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.Stance;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.Threat;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.WorldView;
import com.dwinovo.numen.plugins.minecraft.pathing.spec.PositionCosts;
import com.dwinovo.numen.plugins.minecraft.pathing.world.BodyStats;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Kinematics;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Clearance;
import com.dwinovo.numen.api.entity.Reach;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 目标族:把"怎样算到了"的五种说法编成 {@link Goal}。每一种的定义与判定都写在这里,别处不另判;模块里没有别的宽限。
 *
 * <ol>
 *   <li><b>位置</b>({@link #at}、{@link #column}、{@link #level},同一种目标 {@link Position}):某一格、某一列、某一高度,
 *       坐标给几个算几个。"某一格"是脚所在的那一格,身体怎么待着都算:站着、挂在梯子或藤上、浮在水里。站到一块方块上面就是
 *       到它上面脚所在的那一格,那一格由 {@link #standingOn} 算;</li>
 *   <li><b>距离范围</b>({@link #within}):离一个位置的距离在 [最小, 最大] 之间,距离只按 {@link Position#distanceSqr} 量;</li>
 *   <li><b>够着一格</b>({@link #reach}、{@link #dig}):手够得着那一格方块,去用它、挖它;中间隔着什么不问;</li>
 *   <li><b>够着一个碰撞箱</b>({@link #touch}):手够得着它(要打、要点的那只);</li>
 *   <li><b>远离</b>({@link #awayFrom}):离一组生物都在各自的危险半径之外。</li>
 * </ol>
 * 组合方式:多个取其一({@link #anyOf});几个同时成立({@link #allOf})只给战斗走位
 * ——站在围着目标的环上,同时离别的怪够远。
 *
 * <p>估价的水平底价({@link Goal#estimate}):水平按疾跑走八方向距离,每种目标只估到它自己的边界;升降与挖开的额外代价另算
 * ({@link Vertical}、{@link Burial}),目标只交出到了时脚所在的高度范围({@link Goal#heights})与终点格({@link Goal#endCells})。
 */
public final class Goals {

    private Goals() {}

    // ==================== 位置 ====================

    /** 脚正好在这一格。 */
    public static Position at(BlockPos feet) {
        return new Position(feet.getX(), feet.getY(), feet.getZ());
    }

    /** 那一列,任何高度。 */
    public static Position column(int x, int z) {
        return new Position(x, null, z);
    }

    /** 到某一高度:脚所在的格是第 {@code y} 层,任何 x、z。 */
    public static Position level(int y) {
        return new Position(null, y, null);
    }

    /**
     * 此刻站在 {@code block} 上时脚所在的节点:脚在它自己那一格(下半砖、灵魂沙、耕地)或上面一格(整块、栅栏),身体站得住、
     * 托着脚的就是它;站不上去为 null。
     */
    public static BlockPos standingOn(BlockGetter level, BodyStats body, BlockPos block) {
        for (BlockPos node : List.of(block, block.above())) {
            Stance stance = Stance.at(level, body, node);
            if (stance != null && stance.grounded() && stance.supportY() == block.getY()) {
                return node.immutable();
            }
        }
        return null;
    }

    // ==================== 距离范围 ====================

    /**
     * 离 {@code center} 的距离(按 {@link Position#distanceSqr} 量)在 {@code [min, max]} 之间。{@code max} 可以是无穷大:
     * 离开 {@code min} 以内就行。
     */
    public static Goal within(Position center, double min, double max) {
        if (!(min >= 0) || !(max >= min)) {
            throw new IllegalArgumentException("the distance range needs 0 ≤ min ≤ max: " + min + ".." + max);
        }
        return new Within(center, min, max);
    }

    // ==================== 够着一格 ====================

    /**
     * 够着 {@code target} 这一格方块:站在这里手够得着它(第 0 层 {@link Reach}:眼睛到这一格的距离小于方块交互距离),而且身体不占着
     * 它。去用它(右键)、去挖它是这同一种到达;中间隔着什么不问,那一格里有没有东西也不问。
     */
    public static Goal reach(BlockPos target, BodyStats body) {
        return new Hand(target.immutable(), body);
    }

    /**
     * 要挖的一格与挖它本身的价钱(与路线代价同一个单位;要主人同意的照价乘倍,由调用方按成本模型算好)。
     */
    public record DigTarget(BlockPos cell, double price) {}

    /**
     * 挖 {@code targets} 里的任意一格:够着了任何一格({@link #reach})就算到。停下的价钱是够得着的那些格里挖它的价钱最低的一个,
     * 所以挖起来贵的格(要问主人的)只在便宜的远出它那份价钱时才去。站位每少够着一格,到达价多收一格平走的耗时除以格数(全少了也不到
     * 多走一格),所以走路的价钱差得出一格时仍按近的挑。这份只在到达价里,估价不含它:不相上下的站位之间不保证挑够得着多的那个。
     */
    public static Goal dig(List<DigTarget> targets, BodyStats body) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("digging any one of them needs at least one cell");
        }
        List<Hand> members = new ArrayList<>(targets.size());
        double[] prices = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            members.add(new Hand(targets.get(i).cell().immutable(), body));
            prices[i] = targets.get(i).price();
        }
        return new DigAny(List.copyOf(members), prices);
    }

    // ==================== 够着一个碰撞箱 ====================

    /**
     * 够着 {@code box} 这个碰撞箱:站在这里眼睛到它的最近距离小于 {@code range}(第 0 层 {@link Reach},与近战出手读同一个
     * "够得着")。碰撞箱是调用方此刻量下的,它挪了就交一个新的目标。
     */
    public static Goal touch(AABB box, BodyStats body, double range) {
        if (!(range > 0) || Double.isInfinite(range)) {
            throw new IllegalArgumentException("the interaction range must be a positive finite number: " + range);
        }
        return new Touch(box, body, range);
    }

    // ==================== 远离 ====================

    /** 远离一组生物:脚所在的列离每一只的水平距离都不小于它的危险半径。 */
    public static Goal awayFrom(List<Threat> threats) {
        if (threats.isEmpty()) {
            throw new IllegalArgumentException("a goal to get away from needs at least one target");
        }
        return new AwayFrom(List.copyOf(threats));
    }

    // ==================== 组合 ====================

    /** 多个目标取其一:到了任何一个就算到;按"走过去加到了再付"挑最便宜的那个。 */
    public static Goal anyOf(List<Goal> goals) {
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("any-of needs at least one goal");
        }
        return new AnyOf(List.copyOf(goals));
    }

    /**
     * 几个目标同时成立,每一个都到了才算到。只给战斗走位:站在围着要打的那只的环上,同时离别的怪都够远。估价取各自估价里
     * 最大的那个;到达价相加;为了到得了都别动的格合在一起。
     */
    public static Goal allOf(List<Goal> goals) {
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("all-of needs at least one goal");
        }
        return new AllOf(List.copyOf(goals));
    }

    // ==================== 估价 ====================

    /** 八方向距离(格):斜着走一步前进 √2 格,先斜后直。 */
    private static double octile(double dx, double dz) {
        return Math.min(dx, dz) * Math.sqrt(2) + Math.abs(dx - dz);
    }

    /**
     * 眼睛挪进 {@code target} 那一格的交互距离以内,水平上最少要付多少:眼睛离那一格的水平距离减去交互距离(竖直差为零时水平能容的
     * 最远),乘疾跑一格的耗时。竖直方向要升降几个节点不在这里,由目标的高度范围算({@link Goal#heights()}、{@link Vertical})。
     */
    private static double beyondReach(BlockPos target, int x, int z, double reach, Kinematics.Pace pace) {
        return beyondReach(new AABB(target), x, z, reach, pace);
    }

    /** 同 {@link #beyondReach(BlockPos, int, int, double, Kinematics.Pace)},目标是一个碰撞箱。 */
    private static double beyondReach(AABB box, int x, int z, double reach, Kinematics.Pace pace) {
        double dx = gap(x + 0.5, box.minX, box.maxX);
        double dz = gap(z + 0.5, box.minZ, box.maxZ);
        return pace.sprint() * Math.max(0, Math.hypot(dx, dz) - reach);
    }

    /** 终点格往各个方向最多离碰撞箱这么远(格数)。 */
    private static int span(double reach, double eye) {
        return (int) Math.ceil(reach + eye) + 1;
    }

    /** 脚在 [y, y + 1) 里,眼睛在 [y + eye, y + 1 + eye) 里:取离 {@code box} 最近的高度,量竖直方向的距离。 */
    private static double lift(AABB box, int y, double eye) {
        return Math.max(0, Math.max(box.minY - (y + 1 + eye), y + eye - box.maxY));
    }

    /** 三个方向的距离合起来够得着:终点格与它所在的高度范围用的是同一个判据。 */
    private static boolean near(double dx, double dy, double dz, double reach) {
        return dx * dx + dy * dy + dz * dz < reach * reach;
    }

    /**
     * 脚在一格里任何一个高度时,眼睛(在格的中心列)够得着 {@code box} 的那些格:够着一格、够着一个碰撞箱共用这一份几何,
     * {@code reach} 是调用方按目标给的交互距离(方块或实体)。
     */
    private static LongSet cellsReaching(AABB box, double eye, double reach) {
        int span = span(reach, eye);
        int x0 = (int) Math.floor(box.minX) - span;
        int y0 = (int) Math.floor(box.minY) - span;
        int z0 = (int) Math.floor(box.minZ) - span;
        // 每个方向上离碰撞箱的距离只和这一格的坐标有关:先各量一遍,三层循环里只相加比较
        double[] dxs = new double[(int) Math.max(Math.floor(box.minX), Math.ceil(box.maxX) - 1) + span - x0 + 1];
        double[] dys = new double[(int) Math.max(Math.floor(box.minY), Math.ceil(box.maxY) - 1) + span - y0 + 1];
        double[] dzs = new double[(int) Math.max(Math.floor(box.minZ), Math.ceil(box.maxZ) - 1) + span - z0 + 1];
        for (int i = 0; i < dxs.length; i++) {
            dxs[i] = gap(x0 + i + 0.5, box.minX, box.maxX);
        }
        for (int i = 0; i < dys.length; i++) {
            dys[i] = lift(box, y0 + i, eye);
        }
        for (int i = 0; i < dzs.length; i++) {
            dzs[i] = gap(z0 + i + 0.5, box.minZ, box.maxZ);
        }
        // 先数出有几格再建表:一格格往空表里加,表要翻着倍地扩上七八回
        long[] found = new long[dxs.length * dys.length * dzs.length];
        int count = 0;
        for (int i = 0; i < dxs.length; i++) {
            for (int j = 0; j < dys.length; j++) {
                for (int k = 0; k < dzs.length; k++) {
                    if (near(dxs[i], dys[j], dzs[k], reach)) {
                        found[count++] = BlockPos.asLong(x0 + i, y0 + j, z0 + k);
                    }
                }
            }
        }
        return new LongOpenHashSet(found, 0, count);
    }

    /** {@link #cellsReaching} 里最低与最高的一层,不枚举格:水平上取最近的(为零),竖直上逐层问同一个判据。 */
    private static Goal.Heights heightsReaching(AABB box, double eye, double reach) {
        int span = span(reach, eye);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int y = (int) Math.floor(box.minY) - span; y <= Math.ceil(box.maxY) + span; y++) {
            if (near(0, lift(box, y, eye), 0, reach)) {
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return lo > hi ? null : new Goal.Heights(lo, hi);
    }

    /**
     * 把 {@code more} 并进 {@code into}。逐个加:一次 {@code addAll} 会按两边个数之和扩表,几十个大半重叠的成员(挖同一片里的几十格)
     * 并起来的表比并出的格数大几十倍。
     */
    private static void union(LongOpenHashSet into, LongSet more) {
        for (long cell : more) {
            into.add(cell);
        }
    }

    /** 坐标 {@code v} 离 {@code [min, max]} 这一段有多远:在段里为 0。 */
    private static double gap(double v, double min, double max) {
        return v < min ? min - v : Math.max(0, v - max);
    }

    // ==================== 排障 ====================

    /** 日志里的一格:{@code x,y,z}。 */
    private static String xyz(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 日志里的一组目标:成员多时只列前三个,再注明一共几个。 */
    private static String listed(List<Goal> members) {
        int shown = Math.min(3, members.size());
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < shown; i++) {
            out.append(i > 0 ? " " : "").append(members.get(i));
        }
        if (members.size() > shown) {
            out.append(" … ").append(members.size()).append(" in all");
        }
        return out.append(")").toString();
    }

    /** 成员里有水平位置的,离 {@code from} 最近的那个;都没有为 null。 */
    private static Goal.Anchor nearestAnchor(List<? extends Goal> members, Vec3 from) {
        Goal.Anchor best = null;
        for (Goal g : members) {
            Goal.Anchor own = g.anchor(from);
            if (own != null && (best == null || own.distance(from) < best.distance(from))) {
                best = own;
            }
        }
        return best;
    }

    // ==================== 各个目标 ====================

    /**
     * 位置:某一格({@code x y z} 都给)、某一列(只给 {@code x z})、某一高度(只给 {@code y})。节点是脚所在的那一格,身体在那儿
     * 怎么待着都算——站着、挂在梯子或藤上、浮在水里;那一格待不待得住由搜索的走法判,这里只比坐标。
     *
     * @param x 为 null 时不看 x;与 {@code z} 同给同不给
     * @param y 为 null 时不看高度
     * @param z 为 null 时不看 z
     */
    public record Position(Integer x, Integer y, Integer z) implements Goal {

        /** 某一格或某一列的格心;只给高度没有水平位置。 */
        @Override
        public Anchor anchor(Vec3 from) {
            return x != null ? new Anchor(x + 0.5, z + 0.5) : null;
        }


        public Position {
            if ((x == null) != (z == null)) {
                throw new IllegalArgumentException("x and z must be given together");
            }
            if (x == null && y == null) {
                throw new IllegalArgumentException("a position needs at least a height, or x and z");
            }
        }

        /** 三个坐标都给了:某一格。 */
        public boolean cell() {
            return x != null && y != null;
        }

        /**
         * 节点 {@code (nx, ny, nz)} 到这个位置的距离的平方:脚所在那一格与这个位置在给了的坐标轴上的整数差,平方相加。
         * 距离范围 {@link #within} 只按它量。
         */
        public double distanceSqr(int nx, int ny, int nz) {
            double sum = 0;
            if (x != null) {
                double dx = nx - x;
                double dz = nz - z;
                sum += dx * dx + dz * dz;
            }
            if (y != null) {
                double dy = ny - y;
                sum += dy * dy;
            }
            return sum;
        }

        @Override
        public boolean contains(int nx, int ny, int nz, Stance stance) {
            return distanceSqr(nx, ny, nz) == 0;
        }

        @Override
        public double estimate(int nx, int ny, int nz, Kinematics.Pace pace) {
            return x != null ? pace.sprint() * octile(Math.abs(x - nx), Math.abs(z - nz)) : 0;
        }

        /** 给了高度(某一格、某一高度)就是那一层;只给了列没有范围。 */
        @Override
        public Heights heights() {
            return y != null ? new Heights(y, y) : null;
        }

        /** 某一格就是那一格;列与高度没有边。 */
        @Override
        public LongSet endCells() {
            return cell() ? LongSet.of(BlockPos.asLong(x, y, z)) : null;
        }

        /** 某一格:别往要站的两格里放方块,别挖脚下那一格。列与高度不护着哪一格。 */
        @Override
        public PositionCosts protection() {
            if (!cell()) {
                return PositionCosts.EMPTY;
            }
            BlockPos feet = new BlockPos(x, y, z);
            return PositionCosts.builder().forbid(PositionCosts.Use.PLACE, feet.asLong())
                    .forbid(PositionCosts.Use.PLACE, feet.above().asLong())
                    .forbid(PositionCosts.Use.DIG, feet.below().asLong()).build();
        }

        @Override
        public String toString() {
            if (cell()) {
                return "at(" + x + "," + y + "," + z + ")";
            }
            return x != null ? "column(" + x + "," + z + ")" : "level(" + y + ")";
        }
    }

    /** 距离范围的终点格最多枚举到这个半径(格):再大,枚举出的格比搜索要展开的节点还多。 */
    private static final int ENUMERABLE = 16;

    private record Within(Position center, double min, double max) implements Goal {

        /** 中心的水平位置,离 from 最近的一圈上面向 from 的那一点(在带里就取 from 到中心连线上离中心 d 的那一点)。 */
        @Override
        public Anchor anchor(Vec3 from) {
            Anchor c = center.anchor(from);
            if (c == null) {
                return null;
            }
            double dx = from.x - c.x();
            double dz = from.z - c.z();
            double d = Math.hypot(dx, dz);
            double r = Math.min(Math.max(d, min), max);
            return d < 1e-6 ? new Anchor(c.x() + r, c.z()) : new Anchor(c.x() + dx / d * r, c.z() + dz / d * r);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double d = center.distanceSqr(x, y, z);
            return d >= min * min && d <= max * max;
        }

        /**
         * 太近往外、太远往里,两侧都朝范围带递减。太远时按走到中心的估价、只算伸出范围的那一截的比例:仍朝中心引。
         */
        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            double d = Math.sqrt(center.distanceSqr(x, y, z));
            if (d < min) {
                return (min - d) * pace.sprint();
            }
            if (d > max) {
                return center.estimate(x, y, z, pace) * (d - max) / d;
            }
            return 0;
        }

        /**
         * 范围里的格:中心是某一格、{@code max} 有限时,中心为心、边长 {@code 2·floor(max)+1} 的方块里满足 {@link #contains} 的每一格
         * (到了时脚所在的节点只看这一个距离)。范围大到枚举出来比搜索本身还大({@link #ENUMERABLE} 格以外)、或没有边,
         * 就和别的没有边的目标一样为 null,埋深不加。
         */
        @Override
        public LongSet endCells() {
            if (!center.cell() || !(max <= ENUMERABLE)) {
                return null;
            }
            int r = (int) Math.floor(max);
            LongOpenHashSet cells = new LongOpenHashSet();
            for (int x = center.x() - r; x <= center.x() + r; x++) {
                for (int y = center.y() - r; y <= center.y() + r; y++) {
                    for (int z = center.z() - r; z <= center.z() + r; z++) {
                        double d = center.distanceSqr(x, y, z);
                        if (d >= min * min && d <= max * max) {
                            cells.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }
            }
            return cells;
        }

        /** 中心给了高度(某一格、某一高度)、{@code max} 有限时,到了时脚所在的层不会比中心高低过 {@code floor(max)}:一个超集。 */
        @Override
        public Heights heights() {
            if (center.y() == null || Double.isInfinite(max)) {
                return null;
            }
            int r = (int) Math.floor(max);
            return new Heights(center.y() - r, center.y() + r);
        }

        @Override
        public String toString() {
            return "within(" + center + " " + min + ".." + max + ")";
        }
    }

    private record Hand(BlockPos target, BodyStats body) implements Goal {

        @Override
        public Anchor anchor(Vec3 from) {
            return new Anchor(target.getX() + 0.5, target.getZ() + 0.5);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double feet = stance.feetY();
            // 攀着的站位不算工位:导航收场松开键之后没人按着潜行,身子顺着梯子滑下去,下一次调用时已经不在这一格了
            return stance.kind() != Stance.Kind.CLIMBING
                    && !Clearance.occupies(body, x, feet, z, target)
                    && Reach.reaches(body.eye(x, feet, z), target, body.blockReach());
        }

        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            return beyondReach(target, x, z, body.blockReach(), pace);
        }

        /** 脚在这一格里任何一个高度时眼睛够得着它的那些格,连它自己那一格在内。 */
        @Override
        public LongSet endCells() {
            return cellsReaching(new AABB(target), body.eyeHeight(), body.blockReach());
        }

        /** 终点格所在的高度范围:{@link #endCells} 里最低与最高的一层。 */
        @Override
        public Heights heights() {
            return heightsReaching(new AABB(target), body.eyeHeight(), body.blockReach());
        }

        /** 别挖要够的那一格(那是到了之后动手的一方的事),也别往里放方块把它埋了。 */
        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(PositionCosts.Use.DIG, target.asLong())
                    .forbid(PositionCosts.Use.PLACE, target.asLong()).build();
        }

        @Override
        public String toString() {
            return "reach(" + xyz(target) + ")";
        }
    }

    /** 挖几格里的任意一格,见 {@link #dig(List, BodyStats)}。 */
    private record DigAny(List<Hand> members, double[] prices) implements Goal {

        @Override
        public Anchor anchor(Vec3 from) {
            return nearestAnchor(members, from);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Hand g : members) {
                if (g.contains(x, y, z, stance)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            double min = Double.POSITIVE_INFINITY;
            for (Hand g : members) {
                min = Math.min(min, g.estimate(x, y, z, pace));
            }
            return min;
        }

        /**
         * 够得着的那些格里挖它的价钱最低的一个,加上每一格够不着的那份:一格平走的耗时除以格数。
         * 一格都够不着是 0(不在目标里,搜索不会在这里停)。
         */
        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            double min = Double.POSITIVE_INFINITY;
            int missed = 0;
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).contains(x, y, z, stance)) {
                    min = Math.min(min, prices[i]);
                } else {
                    missed++;
                }
            }
            if (missed == members.size()) {
                return 0;
            }
            return min + missed * Kinematics.walkTicksPerBlock(members.get(0).body()) / members.size();
        }

        @Override
        public LongSet endCells() {
            LongSet cells = new LongOpenHashSet();
            for (Hand g : members) {
                cells.addAll(g.endCells());
            }
            return cells;
        }

        /** 各成员的范围合起来。 */
        @Override
        public Heights heights() {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Hand g : members) {
                Heights own = g.heights();
                lo = Math.min(lo, own.lo());
                hi = Math.max(hi, own.hi());
            }
            return new Heights(lo, hi);
        }

        @Override
        public PositionCosts protection() {
            PositionCosts all = PositionCosts.EMPTY;
            for (Hand g : members) {
                all = all.plus(g.protection());
            }
            return all;
        }

        @Override
        public String toString() {
            return "digAny" + listed(new ArrayList<>(members));
        }
    }

    private record Touch(AABB box, BodyStats body, double range) implements Goal {

        @Override
        public Anchor anchor(Vec3 from) {
            return new Anchor(box.getCenter().x, box.getCenter().z);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return Reach.reaches(body.eye(x, stance.feetY(), z), box, range);
        }

        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            return beyondReach(box, x, z, range, pace);
        }

        /** 脚在一格里任何高度时眼睛够得着它的格(交互距离是这个目标自己的 {@code range}):与 {@link Hand} 同一份几何。 */
        @Override
        public LongSet endCells() {
            return cellsReaching(box, body.eyeHeight(), range);
        }

        @Override
        public Heights heights() {
            return heightsReaching(box, body.eyeHeight(), range);
        }

        @Override
        public String toString() {
            return String.format("touch(%.2f,%.2f,%.2f %.2f)", (box.minX + box.maxX) / 2, box.minY,
                    (box.minZ + box.maxZ) / 2, range);
        }
    }

    private record AwayFrom(List<Threat> threats) implements Goal {

        /** 远离:没有要去的地方。 */
        @Override
        public Anchor anchor(Vec3 from) {
            return null;
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Threat t : threats) {
                double dx = x + 0.5 - t.x();
                double dz = z + 0.5 - t.z();
                if (dx * dx + dz * dz < t.radius() * t.radius()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 势场:每一只按"离它的距离是危险半径的几倍"贡献,越近越贵,几只相加——两只一左一右时,直穿哪一只都不便宜。
         * 贴在半径上时每只贡献 {@link ActionCosts#DANGER_BLOCKS} 格平走的耗时,与走进它半径里一格的代价同一个量级。
         */
        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            double sum = 0;
            for (Threat t : threats) {
                double dx = x + 0.5 - t.x();
                double dy = y - t.y();
                double dz = z + 0.5 - t.z();
                double span = Math.max(1, t.radius());
                double ratio = (dx * dx + dy * dy + dz * dz) / (span * span);
                sum += 1 / Math.max(ratio, 1.0E-3);
            }
            return sum * ActionCosts.DANGER_BLOCKS * pace.walk();
        }

        /** 势场,没有固定的终点。 */
        @Override
        public LongSet endCells() {
            return null;
        }

        @Override
        public Heights heights() {
            return null;
        }

        @Override
        public String toString() {
            return "awayFrom(" + threats.size() + ")";
        }
    }

    private record AnyOf(List<Goal> members) implements Goal {

        @Override
        public Anchor anchor(Vec3 from) {
            return nearestAnchor(members, from);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Goal g : members) {
                if (g.contains(x, y, z, stance)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            double min = Double.POSITIVE_INFINITY;
            for (Goal g : members) {
                min = Math.min(min, g.estimate(x, y, z, pace));
            }
            return min;
        }

        /** 停在这里满足的那些成员里最便宜的到达价;一个都不满足是 0。 */
        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            double min = Double.POSITIVE_INFINITY;
            boolean in = false;
            for (Goal g : members) {
                if (g.contains(x, y, z, stance)) {
                    in = true;
                    min = Math.min(min, g.arrival(level, x, y, z, stance));
                }
            }
            return in ? min : 0;
        }

        /** 各成员的范围合起来;有一个没有范围,合起来也没有。 */
        @Override
        public Heights heights() {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Goal g : members) {
                Heights own = g.heights();
                if (own == null) {
                    return null;
                }
                lo = Math.min(lo, own.lo());
                hi = Math.max(hi, own.hi());
            }
            return new Heights(lo, hi);
        }

        /** 各成员的合在一起;有一个没有边,合起来也没有。 */
        @Override
        public LongSet endCells() {
            LongOpenHashSet all = new LongOpenHashSet();
            for (Goal g : members) {
                LongSet own = g.endCells();
                if (own == null) {
                    return null;
                }
                union(all, own);
            }
            return all;
        }

        @Override
        public PositionCosts protection() {
            PositionCosts all = PositionCosts.EMPTY;
            for (Goal g : members) {
                all = all.plus(g.protection());
            }
            return all;
        }

        @Override
        public String toString() {
            return "anyOf" + listed(members);
        }
    }

    private record AllOf(List<Goal> members) implements Goal {

        /** 每一个都要到:取第一个有水平位置的成员的。 */
        @Override
        public Anchor anchor(Vec3 from) {
            for (Goal g : members) {
                Anchor own = g.anchor(from);
                if (own != null) {
                    return own;
                }
            }
            return null;
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Goal g : members) {
                if (!g.contains(x, y, z, stance)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public double estimate(int x, int y, int z, Kinematics.Pace pace) {
            double max = 0;
            for (Goal g : members) {
                max = Math.max(max, g.estimate(x, y, z, pace));
            }
            return max;
        }

        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            double sum = 0;
            for (Goal g : members) {
                sum += g.arrival(level, x, y, z, stance);
            }
            return sum;
        }

        /** 每一个都要到:任何一个成员的就是超集,取第一个有范围的。 */
        @Override
        public Heights heights() {
            for (Goal g : members) {
                Heights own = g.heights();
                if (own != null) {
                    return own;
                }
            }
            return null;
        }

        /** 每一个都要到:任何一个成员的就是超集,取第一个有边的。 */
        @Override
        public LongSet endCells() {
            for (Goal g : members) {
                LongSet own = g.endCells();
                if (own != null) {
                    return own;
                }
            }
            return null;
        }

        @Override
        public PositionCosts protection() {
            PositionCosts all = PositionCosts.EMPTY;
            for (Goal g : members) {
                all = all.plus(g.protection());
            }
            return all;
        }

        @Override
        public String toString() {
            return "allOf" + listed(members);
        }
    }
}
