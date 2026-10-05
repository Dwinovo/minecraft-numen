package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.pathing.plan.ActionCosts;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Reach;
import com.dwinovo.numen.pathing.world.Sight;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 目标族:把"怎样算到了"的六种说法编成 {@link Goal}。每一种的定义与判定都写在这里,别处不另判;模块里没有别的宽限。
 *
 * <ol>
 *   <li><b>位置</b>({@link #at}、{@link #column}、{@link #level},同一种目标 {@link Position}):某一格、某一列、某一高度,
 *       坐标给几个算几个。"某一格"是脚所在的那一格,身体怎么待着都算:站着、挂在梯子或藤上、浮在水里。站到一块方块上面就是
 *       到它上面脚所在的那一格,那一格由 {@link #standingOn} 算;</li>
 *   <li><b>距离范围</b>({@link #within}):离一个位置的距离在 [最小, 最大] 之间,距离只按 {@link Position#distanceSqr} 量;</li>
 *   <li><b>用</b>({@link #use}):站在那一格方块某个敞开的面前,眼睛直线看得到那一面、点得到它;</li>
 *   <li><b>挖</b>({@link #dig}):手够得着那一格;挡着的可以挖开,同样够得着时挑挡得少的站位;</li>
 *   <li><b>够着</b>({@link #touch}):手够得着一个碰撞箱(要打的那只);</li>
 *   <li><b>远离</b>({@link #awayFrom}):离一组生物都在各自的危险半径之外。</li>
 * </ol>
 * 组合方式:多个取其一({@link #anyOf})、到了再付一笔({@link #priced});几个同时成立({@link #allOf})只给战斗走位
 * ——站在围着目标的环上,同时离别的怪够远。
 *
 * <p>估价按 {@link ActionCosts} 的估价权重:水平走八方向距离、往上按跳、往下按落,每种目标只估到它自己的边界。
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
            throw new IllegalArgumentException("距离范围要 0 ≤ 最小 ≤ 最大:" + min + ".." + max);
        }
        return new Within(center, min, max);
    }

    // ==================== 用 ====================

    /**
     * 用 {@code target} 这一格方块:在 {@code level} 上列出候选站位——只看敞开的面({@link Sight#open}),面前身体待得住
     * ({@link Stance})、不占着它、那一面在交互距离内而且视线上没有硬遮挡({@link Sight#use})的节点。每个节点对每个敞开的面
     * 打一条射线,节点只在够得着的那一小块里,射线数有界。搜索只需走到其中任何一个;到了之后执行层在活世界上用同一个视线函数
     * 复核;隔着的软遮挡清不清归用它的那一方。
     *
     * <p>在调用方给的世界上一次列完,之后不再变:在世界所在的线程上给活世界,或者给一份快照。
     *
     * @throws IllegalArgumentException 那一格没有可点的轮廓(空气、流体),谈不上用它的哪一面
     */
    public static Use use(BlockGetter level, BodyStats body, BlockPos target) {
        BlockPos at = target.immutable();
        if (!Sight.clickable(level, at)) {
            throw new IllegalArgumentException(at + " 没有可点的轮廓");
        }
        List<Direction> open = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (Sight.open(level, at, side)) {
                open.add(side);
            }
        }
        Map<Long, Set<Direction>> stands = new HashMap<>();
        double reach = body.blockReach();
        int span = (int) Math.ceil(reach) + 1;
        int eye = (int) Math.ceil(body.eyeHeight(Pose.STANDING));
        AABB box = new AABB(at);
        for (int x = at.getX() - span; x <= at.getX() + span && !open.isEmpty(); x++) {
            for (int z = at.getZ() - span; z <= at.getZ() + span; z++) {
                for (int y = at.getY() - span - eye; y <= at.getY() + span; y++) {
                    // 脚在这一格里哪个高度都够不着的,不必再看站不站得住
                    if (box.distanceToSqr(new Vec3(x + 0.5, y + 0.5 + body.eyeHeight(Pose.STANDING), z + 0.5))
                            >= (reach + 1) * (reach + 1)) {
                        continue;
                    }
                    Stance stance = Stance.at(level, body, x, y, z);
                    if (stance == null || Clearance.occupies(body, Pose.STANDING, x, stance.feetY(), z, at)) {
                        continue;
                    }
                    Vec3 from = Reach.eye(body, Pose.STANDING, x, stance.feetY(), z);
                    EnumSet<Direction> seen = EnumSet.noneOf(Direction.class);
                    for (Direction side : open) {
                        if (Sight.use(level, from, reach, at, side) != null) {
                            seen.add(side);
                        }
                    }
                    if (!seen.isEmpty()) {
                        stands.put(BlockPos.asLong(x, y, z), Set.copyOf(seen));
                    }
                }
            }
        }
        return new Use(at, body, Map.copyOf(stands), List.copyOf(open));
    }

    // ==================== 挖 ====================

    /**
     * 挖 {@code target}:站在这里手够得着它(第 0 层 {@link Reach},与挖、放读同一个"够得着"),而且身体不占着它。挡着视线的
     * 由挖的一方挖开,不要求到了就看得见;停在一处要先挖开几格硬遮挡才看得见它,就加几份 {@link ActionCosts#SIGHT_BLOCKER}
     * ——同样够得着时,搜索挑挡得少的站位。谁来挖、清得掉哪些格此刻还不知道:挡着的格一律算清得掉({@link Clearing#ANY})。
     */
    public static Goal dig(BlockPos target, BodyStats body) {
        return dig(target, body, Clearing.ANY);
    }

    /**
     * 挖 {@code target},挡着视线的格由 {@code clearing} 这一方清:看得见它的面只算隔着的格都清得掉的那些({@link Sight#dig}),
     * 一面都没有的站位停下也办不成,到达价无穷——搜索挑别的站位,挖的时候先清哪一格也按同一个判据。
     */
    public static Goal dig(BlockPos target, BodyStats body, Clearing clearing) {
        return new Dig(target.immutable(), body, clearing);
    }

    /**
     * 要挖的一格与挖它本身的价钱(与路线代价同一个单位;要主人同意的照价乘倍,由调用方按成本模型算好)。
     */
    public record DigTarget(BlockPos cell, double price) {}

    /**
     * 挖 {@code targets} 里的任意一格:到了任何一格的挖目标({@link #dig(BlockPos, BodyStats, Clearing)},挡着的由
     * {@code clearing} 这一方清)就算到。停下的价钱是够得着的那些格里"到达价 + 挖它的价钱"最低的一个,所以挖起来贵的格(要问
     * 主人的)只在便宜的远出它那份价钱时才去。同样划算的站位里优先一次够得着最多格的——站位每少够着一格,停下多付
     * {@link ActionCosts#WALK_ONE_BLOCK} 除以格数(全少了也不到多走一格),所以走路的价钱差得出一格时仍按近的挑,只在不相上下的
     * 站位之间按够得着几格分先后。
     */
    public static Goal dig(List<DigTarget> targets, BodyStats body, Clearing clearing) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("挖其中任意一格,至少要有一格");
        }
        List<Dig> members = new ArrayList<>(targets.size());
        double[] prices = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            members.add(new Dig(targets.get(i).cell().immutable(), body, clearing));
            prices[i] = targets.get(i).price();
        }
        return new DigAny(List.copyOf(members), prices);
    }

    /**
     * 挖一格的一方清得掉哪些挡着视线的格。给站位定价(搜索时读快照)与挖的时候清遮挡(读活世界)问的是同一个;实现按冻结的
     * 数据回答,可以从任何线程调用。
     */
    @FunctionalInterface
    public interface Clearing {

        /** 挡着的格一律算清得掉:还不知道谁来挖的时候用。 */
        Clearing ANY = (view, pos) -> true;

        /** {@code view} 上的 {@code pos} 这一格挡着视线时,挖的一方清不清得掉它。 */
        boolean clears(WorldView view, BlockPos pos);
    }

    // ==================== 放 ====================

    /**
     * 往 {@code target} 这一格里放方块:站在这里手够得着它(第 0 层 {@link Reach},与挖同一个"够得着"),而且身体不占着它——
     * 自己占着的格放不进去。那一格是不是空的、看不看得见都不问:放的一方照图写进去。路上不往这一格里垫东西。
     */
    public static Goal place(BlockPos target, BodyStats body) {
        return new Place(new Dig(target.immutable(), body, Clearing.ANY));
    }

    // ==================== 够着 ====================

    /**
     * 够着 {@code box} 这个碰撞箱:站在这里眼睛到它的最近距离小于 {@code range}(第 0 层 {@link Reach},与近战出手读同一个
     * "够得着")。碰撞箱是调用方此刻量下的,它挪了就交一个新的目标。
     */
    public static Goal touch(AABB box, BodyStats body, double range) {
        if (!(range > 0) || Double.isInfinite(range)) {
            throw new IllegalArgumentException("交互距离要是正的有限数:" + range);
        }
        return new Touch(box, body, range);
    }

    // ==================== 远离 ====================

    /** 远离一组生物:脚所在的列离每一只的水平距离都不小于它的危险半径。 */
    public static Goal awayFrom(List<Threat> threats) {
        if (threats.isEmpty()) {
            throw new IllegalArgumentException("远离的目标至少要有一只");
        }
        return new AwayFrom(List.copyOf(threats));
    }

    // ==================== 组合 ====================

    /** 多个目标取其一:到了任何一个就算到;按"走过去加到了再付"挑最便宜的那个。 */
    public static Goal anyOf(List<Goal> goals) {
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("多个目标取其一,至少要有一个");
        }
        return new AnyOf(List.copyOf(goals));
    }

    /** 到了 {@code goal} 之后还要付 {@code cost} 刻:成员各带各的价时,搜索按总价挑。 */
    public static Goal priced(Goal goal, double cost) {
        if (!(cost >= 0) || Double.isInfinite(cost)) {
            throw new IllegalArgumentException("到达价要是非负的有限数:" + cost);
        }
        return new Priced(goal, cost);
    }

    /**
     * 几个目标同时成立,每一个都到了才算到。只给战斗走位:站在围着要打的那只的环上,同时离别的怪都够远。估价取各自估价里
     * 最大的那个;到达价相加;要看的取第一个要求视线的;为了到得了都别动的格合在一起。
     */
    public static Goal allOf(List<Goal> goals) {
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("几个目标同时成立,至少要有一个");
        }
        return new AllOf(List.copyOf(goals));
    }

    // ==================== 估价 ====================

    /** 从 {@code (x, y, z)} 到 {@code (tx, ty, tz)} 的估价:水平八方向距离按走,竖直往上按跳、往下按落。 */
    static double point(int x, int y, int z, int tx, int ty, int tz) {
        return horizontal(Math.abs(tx - x), Math.abs(tz - z)) + vertical(ty - y);
    }

    private static double horizontal(double dx, double dz) {
        return (Math.min(dx, dz) * Math.sqrt(2) + Math.abs(dx - dz)) * ActionCosts.ESTIMATE_PER_BLOCK;
    }

    private static double vertical(double rise) {
        return rise > 0 ? rise * ActionCosts.ESTIMATE_UP : -rise * ActionCosts.ESTIMATE_DOWN;
    }

    /**
     * 眼睛(按脚在这一格的底算)挪进 {@code target} 那一格的交互距离以内,最少要付多少:与 {@link #point} 同一套分轴的价,
     * 水平每格按走、往上按跳、往下按落。眼睛离那一格水平差 {@code a}、竖直差 {@code b},交互距离是 {@code r},挪完之后剩下的
     * 水平差 {@code u}、竖直差 {@code v} 要满足 {@code u² + v² < r²};在这些里取价钱最省的一处。
     *
     * <p>不按直线距离一个价钱估:那样目标在正下方深处时,横着走开十几格估价几乎不涨,地表每一格连同它底下挖开的一列都
     * 像离目标一样近,搜索一圈圈铺开。
     */
    private static double beyondReach(BlockPos target, BodyStats body, int x, int y, int z) {
        return beyondReach(new AABB(target), body.blockReach(), body, x, y, z);
    }

    /** 眼睛(按脚在这一格的底算)挪进 {@code box} 的 {@code reach} 以内最少要付多少,同 {@link #beyondReach(BlockPos, BodyStats, int, int, int)}。 */
    private static double beyondReach(AABB box, double reach, BodyStats body, int x, int y, int z) {
        Vec3 eye = Reach.eye(body, Pose.STANDING, x, y, z);
        double dx = gap(eye.x, box.minX, box.maxX);
        double dz = gap(eye.z, box.minZ, box.maxZ);
        double rise = eye.y < box.minY ? box.minY - eye.y : 0;
        double drop = eye.y > box.maxY ? eye.y - box.maxY : 0;
        double perVertical = rise > 0 ? ActionCosts.ESTIMATE_UP : ActionCosts.ESTIMATE_DOWN;
        return intoDisc(Math.sqrt(dx * dx + dz * dz), rise + drop, reach, ActionCosts.ESTIMATE_PER_BLOCK, perVertical);
    }

    /** 坐标 {@code v} 离 {@code [cell, cell + 1]} 这一段有多远:在段里为 0。 */
    private static double gap(double v, int cell) {
        return gap(v, cell, cell + 1);
    }

    /** 坐标 {@code v} 离 {@code [min, max]} 这一段有多远:在段里为 0。 */
    private static double gap(double v, double min, double max) {
        return v < min ? min - v : Math.max(0, v - max);
    }

    /**
     * 平面上从 {@code (a, b)}({@code a, b ≥ 0})挪进以原点为心、半径 {@code r} 的圆,横向每格 {@code alpha}、纵向每格
     * {@code beta},最少付多少。圆上 {@code alpha·u + beta·v} 最大的一点是 {@code r·(alpha, beta)/‖(alpha, beta)‖};它不越过
     * {@code (a, b)} 时就停在那一点,越过了哪一轴就那一轴不挪、只挪另一轴到圆上。
     */
    private static double intoDisc(double a, double b, double r, double alpha, double beta) {
        if (a * a + b * b <= r * r) {
            return 0;
        }
        double norm = Math.sqrt(alpha * alpha + beta * beta);
        if (r * alpha / norm > a) {
            return beta * (b - Math.sqrt(r * r - a * a));
        }
        if (r * beta / norm > b) {
            return alpha * (a - Math.sqrt(r * r - b * b));
        }
        return alpha * a + beta * b - r * norm;
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
            out.append(" …共 ").append(members.size()).append(" 个");
        }
        return out.append(")").toString();
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

        public Position {
            if ((x == null) != (z == null)) {
                throw new IllegalArgumentException("x 与 z 要一起给");
            }
            if (x == null && y == null) {
                throw new IllegalArgumentException("位置至少要给高度,或者 x 与 z");
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
        public double estimate(int nx, int ny, int nz) {
            return (x != null ? horizontal(Math.abs(x - nx), Math.abs(z - nz)) : 0) + (y != null ? vertical(y - ny) : 0);
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

    private record Within(Position center, double min, double max) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double d = center.distanceSqr(x, y, z);
            return d >= min * min && d <= max * max;
        }

        /**
         * 太近往外、太远往里,两侧都朝范围带递减。太远时按走到中心的估价、只算伸出范围的那一截的比例:仍朝中心引,
         * 竖直方向照样按跳与落算。
         */
        @Override
        public double estimate(int x, int y, int z) {
            double d = Math.sqrt(center.distanceSqr(x, y, z));
            if (d < min) {
                return (min - d) * ActionCosts.ESTIMATE_PER_BLOCK;
            }
            if (d > max) {
                return center.estimate(x, y, z) * (d - max) / d;
            }
            return 0;
        }

        @Override
        public String toString() {
            return "within(" + center + " " + min + ".." + max + ")";
        }
    }

    /**
     * 用一格方块:{@link #use} 列出的候选站位。
     *
     * @param target 要用的那一格
     * @param body   列站位时按的身体
     * @param stands 候选站位(脚所在的节点,{@link BlockPos#asLong})到从那儿用得上的面
     * @param open   敞开的面:面前一格让视线过得去;一面都没有就是四面封死
     */
    public record Use(BlockPos target, BodyStats body, Map<Long, Set<Direction>> stands, List<Direction> open)
            implements Goal {

        /** 四面封死:没有一面敞开。 */
        public boolean sealed() {
            return open.isEmpty();
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return stands.containsKey(BlockPos.asLong(x, y, z));
        }

        @Override
        public LongSet endCells() {
            return new LongOpenHashSet(stands.keySet());
        }

        @Override
        public double estimate(int x, int y, int z) {
            return beyondReach(target, body, x, y, z);
        }

        /** 别挖要用的那一格,也别往里放东西;敞开的面前那一格不放方块——放了就把自己要看的那一面堵上了。 */
        @Override
        public PositionCosts protection() {
            PositionCosts.Builder b = PositionCosts.builder().forbid(PositionCosts.Use.DIG, target.asLong())
                    .forbid(PositionCosts.Use.PLACE, target.asLong());
            for (Direction side : open) {
                b.forbid(PositionCosts.Use.PLACE, target.relative(side).asLong());
            }
            return b.build();
        }

        @Override
        public Sighting sight(int x, int y, int z, Stance stance) {
            Set<Direction> faces = stands.get(BlockPos.asLong(x, y, z));
            return faces == null ? null : new Sighting(target, faces);
        }

        @Override
        public String toString() {
            return "use(" + xyz(target) + " 站位 " + stands.size() + ")";
        }
    }

    private record Dig(BlockPos target, BodyStats body, Clearing clearing) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double feet = stance.feetY();
            return !Clearance.occupies(body, Pose.STANDING, x, feet, z, target)
                    && Reach.reaches(body, Pose.STANDING, x, feet, z, target);
        }

        @Override
        public double estimate(int x, int y, int z) {
            return beyondReach(target, body, x, y, z);
        }

        /**
         * 脚在这一格里任何一个高度时眼睛够得着它的那些格,连它自己那一格在内。按整块包围盒量,比 {@link Reach#reaches}(收了
         * {@link Reach#EDGE} 的边)只多不少。
         */
        @Override
        public LongSet endCells() {
            double reach = body.blockReach();
            double eye = body.eyeHeight(Pose.STANDING);
            int span = (int) Math.ceil(reach + eye) + 1;
            LongOpenHashSet out = new LongOpenHashSet();
            for (int x = target.getX() - span; x <= target.getX() + span; x++) {
                for (int y = target.getY() - span; y <= target.getY() + span; y++) {
                    for (int z = target.getZ() - span; z <= target.getZ() + span; z++) {
                        double dx = gap(x + 0.5, target.getX());
                        double dz = gap(z + 0.5, target.getZ());
                        // 脚在 [y, y + 1) 里,眼睛在 [y + eye, y + 1 + eye) 里:取离那一格最近的高度
                        double dy = Math.max(0, Math.max(target.getY() - (y + 1 + eye), y + eye - (target.getY() + 1)));
                        if (dx * dx + dy * dy + dz * dz < reach * reach) {
                            out.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }
            }
            return out;
        }

        /**
         * 从这里的眼睛看它朝着眼睛的各面({@link Sight#faces}),隔着的格都清得掉的那些面里取硬遮挡最少的那一面({@link Sight#dig}),
         * 每一格加一份 {@link ActionCosts#SIGHT_BLOCKER};一面都没有是无穷。它没有轮廓(已经挖掉了)不加。
         */
        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            if (!Sight.clickable(level, target)) {
                return 0;
            }
            Vec3 eye = Reach.eye(body, Pose.STANDING, x, stance.feetY(), z);
            Sight.Trace line = Sight.dig(level, eye, target, Sight.faces(level, eye, target),
                    pos -> clearing.clears(level, pos));
            return line == null ? Double.POSITIVE_INFINITY : line.hard().size() * ActionCosts.SIGHT_BLOCKER;
        }

        /** 别挖要挖的那一格(那是挖的一方的事),也别往里放方块把它埋了。 */
        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(PositionCosts.Use.DIG, target.asLong())
                    .forbid(PositionCosts.Use.PLACE, target.asLong()).build();
        }

        @Override
        public String toString() {
            return "dig(" + xyz(target) + ")";
        }
    }

    /** 挖几格里的任意一格,见 {@link #dig(List, BodyStats)}。 */
    private record DigAny(List<Dig> members, double[] prices) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Dig g : members) {
                if (g.contains(x, y, z, stance)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double estimate(int x, int y, int z) {
            double min = Double.POSITIVE_INFINITY;
            for (Dig g : members) {
                min = Math.min(min, g.estimate(x, y, z));
            }
            return min;
        }

        /**
         * 够得着的那些格里"到达价 + 挖它的价钱"最低的一个,加上每一格够不着的那份:{@link ActionCosts#WALK_ONE_BLOCK} 除以格数。
         * 一格都够不着是 0(不在目标里,搜索不会在这里停)。
         */
        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            double min = Double.POSITIVE_INFINITY;
            int missed = 0;
            for (int i = 0; i < members.size(); i++) {
                Dig g = members.get(i);
                if (g.contains(x, y, z, stance)) {
                    min = Math.min(min, g.arrival(level, x, y, z, stance) + prices[i]);
                } else {
                    missed++;
                }
            }
            if (missed == members.size()) {
                return 0;
            }
            return min + missed * ActionCosts.WALK_ONE_BLOCK / members.size();
        }

        @Override
        public LongSet endCells() {
            LongOpenHashSet all = new LongOpenHashSet();
            for (Dig g : members) {
                all.addAll(g.endCells());
            }
            return all;
        }

        @Override
        public PositionCosts protection() {
            PositionCosts all = PositionCosts.EMPTY;
            for (Dig g : members) {
                all = all.plus(g.protection());
            }
            return all;
        }

        @Override
        public String toString() {
            return "digAny" + listed(new ArrayList<>(members));
        }
    }

    /** 放一格:到没到、估价、终点格与挖那一格同一套几何({@link Dig}),只是不为看得见它加价,也不禁止挖它。 */
    private record Place(Dig reach) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return reach.contains(x, y, z, stance);
        }

        @Override
        public double estimate(int x, int y, int z) {
            return reach.estimate(x, y, z);
        }

        @Override
        public LongSet endCells() {
            return reach.endCells();
        }

        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(PositionCosts.Use.PLACE, reach.target().asLong()).build();
        }

        @Override
        public String toString() {
            return "place(" + xyz(reach.target()) + ")";
        }
    }

    private record Touch(AABB box, BodyStats body, double range) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return Reach.reaches(body, Pose.STANDING, x, stance.feetY(), z, box, range);
        }

        @Override
        public double estimate(int x, int y, int z) {
            return beyondReach(box, range, body, x, y, z);
        }

        @Override
        public String toString() {
            return String.format("touch(%.2f,%.2f,%.2f %.2f)", (box.minX + box.maxX) / 2, box.minY,
                    (box.minZ + box.maxZ) / 2, range);
        }
    }

    private record AwayFrom(List<Threat> threats) implements Goal {
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
         * 贴在半径上时每只贡献 {@link ActionCosts#DANGER_PER_CELL},与走进它半径里一格的代价同一个量级。
         */
        @Override
        public double estimate(int x, int y, int z) {
            double sum = 0;
            for (Threat t : threats) {
                double dx = x + 0.5 - t.x();
                double dy = y - t.y();
                double dz = z + 0.5 - t.z();
                double span = Math.max(1, t.radius());
                double ratio = (dx * dx + dy * dy + dz * dz) / (span * span);
                sum += 1 / Math.max(ratio, 1.0E-3);
            }
            return sum * ActionCosts.DANGER_PER_CELL;
        }

        @Override
        public String toString() {
            return "awayFrom(" + threats.size() + ")";
        }
    }

    private record AnyOf(List<Goal> members) implements Goal {
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
        public double estimate(int x, int y, int z) {
            double min = Double.POSITIVE_INFINITY;
            for (Goal g : members) {
                min = Math.min(min, g.estimate(x, y, z));
            }
            return min;
        }

        /** 停在这里满足的成员里有一个不要求视线,就不要求;否则要看第一个成员要看的。 */
        @Override
        public Sighting sight(int x, int y, int z, Stance stance) {
            Sighting needed = null;
            for (Goal g : members) {
                if (!g.contains(x, y, z, stance)) {
                    continue;
                }
                Sighting own = g.sight(x, y, z, stance);
                if (own == null) {
                    return null;
                }
                if (needed == null) {
                    needed = own;
                }
            }
            return needed;
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

        /** 各成员的合在一起;有一个没有边,合起来也没有。 */
        @Override
        public LongSet endCells() {
            LongOpenHashSet all = new LongOpenHashSet();
            for (Goal g : members) {
                LongSet own = g.endCells();
                if (own == null) {
                    return null;
                }
                all.addAll(own);
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
        public double estimate(int x, int y, int z) {
            double max = 0;
            for (Goal g : members) {
                max = Math.max(max, g.estimate(x, y, z));
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

        @Override
        public Sighting sight(int x, int y, int z, Stance stance) {
            for (Goal g : members) {
                Sighting own = g.sight(x, y, z, stance);
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

    private record Priced(Goal inner, double cost) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return inner.contains(x, y, z, stance);
        }

        @Override
        public double estimate(int x, int y, int z) {
            return inner.estimate(x, y, z) + cost;
        }

        /** 这一笔,加上里面那个目标自己停在这儿要付的。 */
        @Override
        public double arrival(WorldView level, int x, int y, int z, Stance stance) {
            return cost + inner.arrival(level, x, y, z, stance);
        }

        @Override
        public Sighting sight(int x, int y, int z, Stance stance) {
            return inner.sight(x, y, z, stance);
        }

        @Override
        public LongSet endCells() {
            return inner.endCells();
        }

        @Override
        public PositionCosts protection() {
            return inner.protection();
        }

        @Override
        public String toString() {
            return inner + "+" + cost;
        }
    }
}
