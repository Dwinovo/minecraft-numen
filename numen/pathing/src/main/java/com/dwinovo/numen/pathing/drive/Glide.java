package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 一次滑翔的计划:从哪起飞、沿什么线飞到目标上空。滑翔不进格子搜索(转弯半径大、停不下来,状态里得有速度),是行程里的一段({@link Voyage}),
 * 这里是上层:定起飞点、巡航高度与线,下层是局部控制器({@link Pilot},物理 {@link Dynamics#gliding})。叫了 {@code move.glide} 就滑翔,不和走路比价钱;
 * 飞不起来(缺鞘翅、烟花、耐久、起飞的地方、线、降落的地)在受理时当场拒绝({@link Lack})。
 *
 * <h2>起飞</h2>
 * 一趟飞是:走到起飞点 → 起飞 → 巡航 → 进入目标 {@link #DESCENT_RANGE} 格就降落,落地就是这一趟的结束。起飞点照 MovementSync 的做法:身体附近找一根宽口径、
 * 直到巡航高度都空着的柱子({@link #column}),走过去,在柱子里陡爬升({@link #TAKEOFF_PITCH}),爬到巡航高度再转向目标。候选在附近铺开({@link #takeoffs}:每隔
 * {@link #TAKEOFF_SPACING} 格一列,站得住的地面,按离身体的远近从近到远试)。受理只做这几何的检查,不模拟飞行;柱子里贴着柱子爬是局部控制器({@link Pilot})的事。
 * 烟花不够(少于 {@link #ROCKET_FLOOR} 支)或排不出这样的起飞点:走到一处落差够大的边沿,从边沿走出去、离地后展开,只靠高度往下滑({@link #dropping}),没有爬升与巡航。
 *
 * <h2>线</h2>
 * 起飞点→爬到巡航高度→朝目标飞。巡航高度:沿起飞点到目标的直线每 {@link #CRUISE_SAMPLE} 格取一列,看这一列最高的碰撞面
 * ({@link Terrain#top}),取其中最高的再加 {@link #CRUISE_MARGIN} 格;线是柱子里竖直爬到这个高度,再朝目标平飞到目标上空。
 * 没加载的区块是未知,不是空气也不是墙:排线时先当过得去,{@link Sortie} 每刻接着查前方新加载的那段线({@link Helm#blocked}),被挡就沿航线往前拉升重排({@link #raise})或改降。
 *
 * <h2>降落</h2>
 * 照 Baritone:水平进入目标 {@link #DESCENT_RANGE} 格({@link Sortie})才选落点,落点只求安全({@link Landings}:天然实心、四周八格也安全、头顶有空柱),不为离目标近而挑点;
 * 先保持高度飞到落点正上方的柱口({@link #descent}),不贴地、不在低空绕;到了柱口,控制器的打分换成"到确切落点的距离",沿柱一边绕一边沉({@link #shaft});
 * 掉到柱底(落点那一格的地面)以下还没落地,这个落点拉黑换下一个,日志与回执都写。找不到落点就在高处盘旋等。
 * 低空追着线贴地飞会撞上线下面的坡与台阶、落进水里、擦着树冠(控制器总落在线下面一到两格),所以最后一段不追线。
 * 迫降(没进目标范围就得落地:烟花、耐久到了下限,或前方被挡)选点同一套。
 *
 * <h2>烟花与耐久</h2>
 * 不要求起飞前备够全程(Baritone):烟花不少于 {@link #ROCKET_FLOOR} 支、鞘翅耐久不少于 {@link #WEAR_FLOOR} 点才起飞,起飞时就不够当场拒绝;飞着少于这个数就近降落
 * ({@link Sortie}),没进目标范围就落了地、离目标超过 {@link #REFLY_RANGE} 格、够用就从此刻的位置再起飞({@link Voyage}),不够就收工。
 */
final class Glide {

    /** 烟花少于这么多支就不起飞、飞着就近降落;鞘翅耐久少于这么多点同样(Baritone 的 {@code elytraMinFireworksBeforeLanding} 与 {@code elytraMinimumDurability})。 */
    static final int ROCKET_FLOOR = 5;
    static final int WEAR_FLOOR = 5;
    /** 水平进入目标这么多格就转入降落(MovementSync 的默认到达半径);离目标不超过这么远,这一趟滑翔就太近了。 */
    static final double DESCENT_RANGE = 16.0;
    /** 没进目标范围就落了地,离目标超过这么远(水平)才从此刻再起飞(MovementSync 选择飞的门槛);不超过就收工。 */
    static final double REFLY_RANGE = 96.0;
    /** 巡航高度:沿线每隔几格看一列的最高碰撞面,取最高的再加几格(MovementSync 的做法)。 */
    private static final int CRUISE_SAMPLE = 16;
    private static final int CRUISE_MARGIN = 16;
    /** 起飞抬头的俯仰(度):陡抬但不到垂直(REPORT-feel 对起飞的建议),在空柱里爬;爬到巡航高度才转向目标。 */
    static final float TAKEOFF_PITCH = -75.0F;
    /** 滑翔比:每下降一格水平走几格(鞘翅平滑翔大约八九比一)。 */
    static final double GLIDE_RATIO = 9.0;
    /** 线两侧各留几格也要飞得过去。 */
    private static final int SIDE = 2;
    /** 抬高巡航每次多几格(排线读的高度图每 {@link #CRUISE_SAMPLE} 格取一列,漏掉的细墙,往上一格一格试)。 */
    private static final int CLIMB_STEP = 6;
    /** 飞着遇到障碍沿航线往前拉升:每升一格往前走几格(约 27 度,控制器贴得住;更陡的线它追不上,落在线后面一大截)。 */
    private static final double CLIMB_RUN = 2.0;
    /** 拉升时身体落在线后面的高度(格):量到过的拉升,身体比线低几格。 */
    private static final double CLIMB_LAG = 4.0;
    /** 拉升时每刻大约升几格(烟花推着往上爬,量到每刻一格到一格多):一支烟花推 {@code rocketBurn} 刻,就升这么多乘 {@code rocketBurn} 格。 */
    private static final double RISE_PER_TICK = 1.0;
    /** 落地离降落点水平不超过这么远就算降到了(演练与实飞同一个判据,飞行线的降落点那一圈就是它)。 */
    static final double TOLERANCE = 6.0;
    /** 够得着落点留的余量:按滑翔比算出的射程只取这么多。 */
    static final double REACH_MARGIN = 0.8;
    /** 从边沿走出去的滑翔,降落点取在目标这一侧多少格处(落在目标的 {@link #DESCENT_RANGE} 格里)。 */
    private static final double DROP_SHORT = DESCENT_RANGE / 2;
    /**
     * 算力预算(不是物理的判据):起飞点往四周找多少格、每隔几格取一列、最多试几个(每个是一根柱子的扫掠检查);
     * 找边沿往四周看几格、最多试几个;选降落点最多扫几个落点的线({@link #LINES_TRIED},落点往四周找多少圈见 {@link Landings#RADIUS})。
     */
    private static final int TAKEOFF_RADIUS = 24;
    private static final int TAKEOFF_SPACING = 3;
    private static final int TAKEOFFS_TRIED = 64;
    private static final int EDGE_RADIUS = 16;
    private static final int EDGES_TRIED = 4;
    private static final int LINES_TRIED = 16;
    /** 起飞点从身体脚下往上、往下各看几层地面(走得过去的高低)。 */
    private static final int TAKEOFF_UP = 3;
    private static final int TAKEOFF_DOWN = 6;
    /** 没有烟花时下滑线能飞多远(滑翔比的这个比例,留余量)。 */
    private static final double GLIDE_SHARE = 0.65;
    /**
     * 一份滑翔计划。
     *
     * @param drop      从边沿走出去起飞(不起跳、不爬升);否则平地起跳
     * @param takeoff   起飞的格(站着的节点)
     * @param pitch     起跳后抬头的俯仰(度);从边沿走出去时是 0
     * @param landing   从边沿走出去的滑翔:线的终点,安全的落点;平地起飞的为 null(降落时才选)
     * @param way       沿着飞的线:平地起飞的是爬到巡航高度再朝目标平飞,从边沿走出去的是到落点的下滑线
     * @param blocks    起飞点到目标的水平距离
     * @param aim       这一趟飞往的水平位置
     */
    record Plan(boolean drop, BlockPos takeoff, float pitch, BlockPos landing, Airway way, double blocks, Goal.Anchor aim) {}

    /** 一次试排:成了是计划,没成是原因。 */
    private record Attempt(Plan plan, Lack.Why why) {

        static Attempt of(Plan plan) {
            return new Attempt(plan, null);
        }

        static Attempt not(Lack.Why why) {
            return new Attempt(null, why);
        }
    }

    private Glide() {}

    /**
     * 受理:这一趟从这里飞得起来吗。缺鞘翅、耐久、烟花(又没有从边沿滑下去的地方)、起飞点、线、降落的地,都是 {@link Lack};{@code spent} 是这一趟里已经失败过的起飞点,不再用。
     */
    static Admission admit(Craft craft, Goal goal, Set<BlockPos> spent) {
        BodySnapshot snapshot = craft.snapshot();
        Mobility mobility = snapshot.mobility();
        if (!mobility.canGlide()) {
            return lacking(craft, Lack.Why.NO_WINGS, 0, 0);
        }
        if (mobility.gliderLife() < WEAR_FLOOR) {
            return lacking(craft, Lack.Why.WORN, WEAR_FLOOR, mobility.gliderLife());
        }
        Goal.Anchor target = goal.anchor(craft.position());
        if (target == null) {
            return lacking(craft, Lack.Why.NO_PLACE, 0, 0);
        }
        double range = target.distance(craft.position());
        if (range <= DESCENT_RANGE) {
            return lacking(craft, Lack.Why.TOO_NEAR, (int) DESCENT_RANGE, (int) Math.round(range));
        }
        BodyStats stats = snapshot.stats();
        if (!landable(craft, stats, target)) {
            return lacking(craft, Lack.Why.NO_LANDING, Landings.RADIUS, 0);
        }
        Plan chosen = null;
        Lack.Why why = Lack.Why.NO_TAKEOFF;
        if (mobility.fireworks() >= ROCKET_FLOOR) {
            Attempt attempt = climbing(craft, stats, target, spent);
            chosen = attempt.plan();
            why = attempt.why() != null ? attempt.why() : why;
        }
        if (chosen == null) {
            chosen = dropping(craft, stats, target, spent);
        }
        if (chosen == null) {
            return mobility.fireworks() < ROCKET_FLOOR ? lacking(craft, Lack.Why.TOO_FEW_ROCKETS, ROCKET_FLOOR, mobility.fireworks())
                    : lacking(craft, why, why == Lack.Why.NO_TAKEOFF ? TAKEOFF_RADIUS : 0, 0);
        }
        return Admission.glide(chosen);
    }

    private static Admission lacking(Craft craft, Lack.Why why, int need, int have) {
        Lack lack = new Lack(why, need, have);
        PathLog.debug("{} 飞不起来:{}", craft.who(), lack);
        return Admission.lacking(lack);
    }

    /**
     * 目标附近有安全的落点,或那一片没加载(没加载的读出来是空的,先当有:进了目标范围、那片加载了再选真正的落点)。
     * 找到了就行,不看够不够得着:那是降落时的事。
     */
    private static boolean landable(Craft craft, BodyStats stats, Goal.Anchor target) {
        int x = (int) Math.floor(target.x());
        int z = (int) Math.floor(target.z());
        return !craft.world().isLoaded(x, z) || Landings.nearest(craft, stats, x, z, 1, spot -> spot) != null;
    }

    /**
     * 有烟花的起飞:附近铺开的起飞点从近到远,取第一个排得成的({@link #flight})。算力预算:最多试 {@link #TAKEOFFS_TRIED} 个起飞点。
     * 没成的原因取最能说明问题的一个:有线过不去({@link Lack.Why#NO_ROUTE})比头顶没开阔({@link Lack.Why#NO_TAKEOFF})更具体。
     */
    private static Attempt climbing(Craft craft, BodyStats stats, Goal.Anchor target, Set<BlockPos> spent) {
        Lack.Why last = Lack.Why.NO_TAKEOFF;
        for (BlockPos takeoff : takeoffs(craft, stats, spent)) {
            Attempt attempt = flight(craft, stats, takeoff, target);
            if (attempt.plan() != null) {
                return attempt;
            }
            if (attempt.why() == Lack.Why.NO_ROUTE) {
                last = Lack.Why.NO_ROUTE;
            }
        }
        return Attempt.not(last);
    }

    /**
     * 起飞点:身体附近每隔 {@link #TAKEOFF_SPACING} 格一列、从身体高度上下 {@link #TAKEOFF_UP}、{@link #TAKEOFF_DOWN} 层里站得住的干地,
     * 离身体近的在前(走过去的路程从近到远),没用过的,最多 {@link #TAKEOFFS_TRIED} 个。身体自己站着的那一格在最前。
     */
    private static List<BlockPos> takeoffs(Craft craft, BodyStats stats, Set<BlockPos> spent) {
        BlockPos at = craft.cell();
        List<BlockPos> out = new ArrayList<>();
        Stance own = Stance.at(craft.world(), stats, at);
        if (own != null && own.grounded() && !spent.contains(at)) {
            out.add(at);
        }
        List<BlockPos> around = new ArrayList<>();
        for (int x = -TAKEOFF_RADIUS; x <= TAKEOFF_RADIUS; x += TAKEOFF_SPACING) {
            for (int z = -TAKEOFF_RADIUS; z <= TAKEOFF_RADIUS; z += TAKEOFF_SPACING) {
                BlockPos cell = ground(craft, stats, at.getX() + x, at.getZ() + z, at.getY() + TAKEOFF_UP, at.getY() - TAKEOFF_DOWN);
                if (cell != null && !cell.equals(at) && !spent.contains(cell)) {
                    around.add(cell);
                }
            }
        }
        around.sort(Comparator.comparingDouble(cell -> cell.distSqr(at)));
        out.addAll(around);
        return out.subList(0, Math.min(out.size(), TAKEOFFS_TRIED));
    }

    /**
     * 从 {@code takeoff} 起飞去 {@code target} 的一次试排:巡航高度({@link #cruise})定了,起飞点那根柱子要直到巡航高度都空着({@link #column}),
     * 柱顶朝目标平飞的线沿途也扫得过去({@link #corridor})。线是 起飞点 → 柱顶 → 目标上空,检查与飞行读的是同一条。不模拟飞行。
     */
    private static Attempt flight(Craft craft, BodyStats stats, BlockPos takeoff, Goal.Anchor target) {
        Vec3 p0 = new Vec3(takeoff.getX() + 0.5, takeoff.getY(), takeoff.getZ() + 0.5);
        double dx = target.x() - p0.x;
        double dz = target.z() - p0.z;
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        double ux = dx / range;
        double uz = dz / range;
        double cruise = cruise(craft, p0, ux, uz, range);
        if (!column(craft, stats, p0, cruise)) {
            return Attempt.not(Lack.Why.NO_TAKEOFF);
        }
        // 柱子里一直爬到巡航高度(沿线最高的碰撞面再加一截,附近的障碍都在它之下),再朝目标飞
        Vec3 p1 = new Vec3(p0.x, cruise, p0.z);
        // 巡航高度平飞到目标上空:进了目标 DESCENT_RANGE 格才选落点、沿柱下降,在那之前不放高度
        List<Vec3> corners = List.of(p1, new Vec3(target.x(), cruise, target.z()));
        if (!corridor(craft, stats, corners, ux, uz)) {
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        List<Vec3> line = new ArrayList<>(corners);
        line.add(0, p0);
        Airway way = Airway.through(line, null).withClimb(cruise - p0.y);
        return Attempt.of(new Plan(false, takeoff, TAKEOFF_PITCH, null, way, range, target));
    }

    /**
     * 起飞点是一根宽口径的空柱:从脚底到 {@code top} 高度,身体盒扫过去不碰方块,中心与四角各 {@link #SIDE} 格外的五条竖线都是(口径只在 REPORT 里写"宽",
     * 取和线两侧同一个 {@link #SIDE})。没加载的区块读出来是空的,先当空着,飞到时再查({@link Helm#blocked})。
     */
    private static boolean column(Craft craft, BodyStats stats, Vec3 base, double top) {
        Airway.Sweep sweep = Airway.sweep(craft.world(), stats, 0);
        Vec3 up = new Vec3(0, top - base.y, 0);
        for (int[] at : new int[][] {{0, 0}, {-SIDE, -SIDE}, {-SIDE, SIDE}, {SIDE, -SIDE}, {SIDE, SIDE}}) {
            Vec3 from = base.add(at[0], 0, at[1]);
            if (!sweep.clear(from, from.add(up))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 巡航高度:沿 {@code p0} 朝 {@code (ux, uz)} 到目标的直线,每 {@link #CRUISE_SAMPLE} 格取一列(起点与目标那一列也取),看这一列最高的碰撞面({@link Terrain#top}),
     * 取其中最高的再加 {@link #CRUISE_MARGIN} 格;最高到建筑高度的顶。
     */
    private static double cruise(Craft craft, Vec3 p0, double ux, double uz, double range) {
        double highest = p0.y;
        for (double along = 0; ; along += CRUISE_SAMPLE) {
            double s = Math.min(along, range);
            highest = Math.max(highest, craft.world().top((int) Math.floor(p0.x + ux * s), (int) Math.floor(p0.z + uz * s)));
            if (s >= range) {
                break;
            }
        }
        return Math.min(highest + CRUISE_MARGIN, craft.world().getMinY() + craft.world().getHeight() - 2);
    }

    /**
     * 从边沿走出去的计划:身体附近落差大的边沿(那一格站得住,朝目标走过去的前面没有地面),落差大的先试,最多试 {@link #EDGES_TRIED} 个;
     * 线是边沿到落点的直线下滑线,落点是安全的({@link Landings#safe})、离边沿不超过滑翔比的 {@link #GLIDE_SHARE}。没有烟花时落点够不够得着全靠已知的地面,
     * 所以落点必须是真的(那片没加载的不排)。没有为 null。
     */
    private static Plan dropping(Craft craft, BodyStats stats, Goal.Anchor target, Set<BlockPos> spent) {
        BlockPos at = craft.cell();
        double dx = target.x() - craft.position().x;
        double dz = target.z() - craft.position().z;
        double length = Math.hypot(dx, dz);
        if (length < 1) {
            return null;
        }
        int ax = (int) Math.round(dx / length * 2);
        int az = (int) Math.round(dz / length * 2);
        int floorY = craft.world().getMinY();
        record Edge(BlockPos cell, int drop) {}
        List<Edge> edges = new ArrayList<>();
        for (int x = -EDGE_RADIUS; x <= EDGE_RADIUS; x++) {
            for (int z = -EDGE_RADIUS; z <= EDGE_RADIUS; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos cell = at.offset(x, y, z);
                    Stance stance = Stance.at(craft.world(), stats, cell);
                    if (stance == null || !stance.grounded() || spent.contains(cell)) {
                        continue;
                    }
                    BlockPos beyond = cell.offset(ax, 0, az);
                    Stance ahead = Stance.at(craft.world(), stats, beyond);
                    if (ahead == null || !ahead.grounded()) {
                        BlockPos floor = ground(craft, stats, beyond.getX(), beyond.getZ(), cell.getY(), floorY);
                        edges.add(new Edge(cell, cell.getY() - (floor == null ? floorY : floor.getY())));
                    }
                }
            }
        }
        edges.sort(Comparator.comparingInt((Edge e) -> -e.drop()).thenComparingDouble(e -> e.cell().distSqr(at)));
        for (Edge edge : edges.subList(0, Math.min(edges.size(), EDGES_TRIED))) {
            Plan plan = fromEdge(craft, stats, edge.cell(), target);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Plan fromEdge(Craft craft, BodyStats stats, BlockPos edge, Goal.Anchor target) {
        double dx = target.x() - (edge.getX() + 0.5);
        double dz = target.z() - (edge.getZ() + 0.5);
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return null;
        }
        double ux = dx / range;
        double uz = dz / range;
        double y0 = edge.getY();
        // 落点:沿线离边沿 d 格的安全落点;d 受高度限制(高度要够滑过去),也不越过目标这一侧的 DROP_SHORT 格。没有烟花只能往下滑,落点必须比边沿低
        double d = Math.max(1, range - DROP_SHORT);
        BlockPos landing = null;
        for (int round = 0; round < 3; round++) {
            landing = Landings.safe(craft, stats, (int) Math.floor(edge.getX() + 0.5 + ux * d), (int) Math.floor(edge.getZ() + 0.5 + uz * d));
            if (landing == null) {
                return null;
            }
            double reach = (y0 - landing.getY()) * GLIDE_RATIO * GLIDE_SHARE;
            if (reach >= d) {
                break;
            }
            d = reach;
            landing = null;
            if (d < 1) {
                return null;
            }
        }
        if (landing == null) {
            return null;
        }
        Vec3 p0 = new Vec3(edge.getX() + 0.5, y0, edge.getZ() + 0.5);
        Vec3 touchdown = new Vec3(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        if (!corridor(craft, stats, List.of(p0.add(ux * 1.2, 0.2, uz * 1.2), touchdown), ux, uz)) {
            return null;
        }
        Airway way = Airway.through(List.of(p0, touchdown), touchdown).withZone(TOLERANCE);
        return new Plan(true, edge, 0, landing, way, Math.hypot(landing.getX() - edge.getX(), landing.getZ() - edge.getZ()), target);
    }

    /** 沿 {@code corners} 这条折线飞,线上和线两侧各 {@link #SIDE} 格的平行线都扫得过去(身体盒不碰方块)。没加载的区块读出来是空的:先当过得去,飞到时再查。 */
    private static boolean corridor(Craft craft, BodyStats stats, List<Vec3> corners, double ux, double uz) {
        Airway.Sweep sweep = Airway.sweep(craft.world(), stats, 0);
        for (int side : new int[] {0, -SIDE, SIDE}) {
            Vec3 shift = new Vec3(-uz * side, 0, ux * side);
            for (int i = 0; i + 1 < corners.size(); i++) {
                if (!sweep.clear(corners.get(i).add(shift), corners.get(i + 1).add(shift))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 这一列从 {@code from} 往下到 {@code to} 第一个站得住的干地格(地面);没有为 null。往下遇到的第一个落脚处是液体(脚或头在水里、岩浆里)就是没有。
     * 起飞点与边沿的站位用它;降落点的判据在 {@link Landings}。
     */
    static BlockPos ground(Craft craft, BodyStats stats, int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockPos cell = new BlockPos(x, y, z);
            if (!craft.world().getFluidState(cell).isEmpty()) {
                return null;
            }
            Stance stance = Stance.at(craft.world(), stats, cell);
            if (stance != null && stance.grounded()) {
                return craft.world().getFluidState(cell.above()).isEmpty() ? cell : null;
            }
        }
        return null;
    }

    // ==================== 降落 ====================

    /** 在落点正上方(柱口)这么高(格):柱里一边绕一边沉,大圈每圈沉十来格,要留够转一圈多的高度。 */
    private static final double HOVER = 12.0;

    /** 降落的结论:落点,和从此刻的位置飞到它正上方(柱口)的线。 */
    record Descent(BlockPos spot, Airway approach) {}

    /**
     * 降落(Baritone 的做法):从空中的 {@code pos} 起,先看正下方那一列,再由近到远({@link Landings#nearest}),取第一个<b>够得着</b>(按滑翔比到它正上方 {@link #HOVER} 格,
     * 有烟花算上烟花换来的高度,{@link #reaches})、线沿途扫得过去({@link #corridor})的安全落点,和往它正上方飞的线:保持高度(只按滑翔比沉到够得着的那一点)平飞到柱口,
     * 不贴地、不在低空绕。到了柱口再换成沿柱下降({@link #shaft})。{@code barred} 是这一段里拉黑了的落点。算力预算:最多扫 {@link #LINES_TRIED} 个落点的线。没有为 null。
     */
    static Descent descent(Craft craft, Vec3 pos, Mobility mobility, Set<BlockPos> barred) {
        BodyStats stats = craft.snapshot().stats();
        double lift = lift(mobility);
        int x = (int) Math.floor(pos.x);
        int z = (int) Math.floor(pos.z);
        return Landings.nearest(craft, stats, x, z, LINES_TRIED, spot -> {
            if (barred.contains(spot)) {
                return null;
            }
            Vec3 mouth = new Vec3(spot.getX() + 0.5, spot.getY() + HOVER, spot.getZ() + 0.5);
            if (!reaches(pos, mouth, lift)) {
                return null;
            }
            double run = Math.hypot(mouth.x - pos.x, mouth.z - pos.z);
            Vec3 over = new Vec3(mouth.x, Math.max(mouth.y, pos.y - run / (GLIDE_RATIO * REACH_MARGIN)), mouth.z);
            double ux = run < 1 ? heading(craft).x : (over.x - pos.x) / run;
            double uz = run < 1 ? heading(craft).z : (over.z - pos.z) / run;
            List<Vec3> corners = List.of(pos, over);
            return corridor(craft, stats, corners, ux, uz) ? new Descent(spot, Airway.through(corners, null)) : null;
        });
    }

    /**
     * 沿柱下降:只有一个点的线,点是落点的地面(降落点),控制器({@link Pilot})的打分就是到这个确切点的距离({@code NEAR} 那一档),偏航只管水平对准;
     * 扫掠不碰方块、触地时竖直速度高于每刻 {@link Dynamics#SLOW_FALL} 且不伤身,都是控制器已有的硬约束。
     */
    static Airway shaft(BlockPos spot) {
        Vec3 touchdown = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        return Airway.through(List.of(touchdown), touchdown).withZone(TOLERANCE);
    }

    /** 身体此刻水平飞的方向(单位向量);几乎没有水平速度就取视线的朝向。 */
    static Vec3 heading(Craft body) {
        Vec3 v = body.velocity();
        double speed = Math.hypot(v.x, v.z);
        return speed > 0.05 ? new Vec3(v.x / speed, 0, v.z / speed)
                : new Vec3(-Math.sin(Math.toRadians(body.yaw())), 0, Math.cos(Math.toRadians(body.yaw())));
    }

    /** 烟花能多换几格高度:留下 {@link #ROCKET_FLOOR} 支之外的烟花,一支升 {@link #RISE_PER_TICK} × {@code rocketBurn} 格。 */
    static double lift(Mobility mobility) {
        return Math.max(0, mobility.fireworks() - ROCKET_FLOOR) * mobility.rocketBurn() * RISE_PER_TICK;
    }

    /** 从 {@code from} 滑得到 {@code to}:已经在它那一圈({@link #TOLERANCE})之内,或按滑翔比(留余量 {@link #REACH_MARGIN})、加上烟花换来的高度 {@code lift} 滑得到。 */
    static boolean reaches(Vec3 from, Vec3 to, double lift) {
        double distance = Math.hypot(to.x - from.x, to.z - from.z);
        return distance <= TOLERANCE || (from.y - to.y + lift) * GLIDE_RATIO * REACH_MARGIN >= distance;
    }

    // ==================== 边飞边看 ====================

    /**
     * 前方被挡:从空中的 {@code pos} 沿航线往前拉升再重排一条线——边往前飞边升到过得去的高度(每升一格往前走 {@link #CLIMB_RUN} 格,烟花推着升),
     * 再朝目标上空平飞。不停下来原地竖直往上爬:身体正以每刻一格多的速度往前飞,真正爬的地方早已不是查过的那一列。
     * 高度从 {@link #cruise} 往目标读高度图的结果(已含 {@link #CRUISE_MARGIN} 的余量)起,一格一格往上试({@link #CLIMB_STEP}),
     * 沿线(含升的那一段)两侧都扫得过去才算;烟花少于 {@link #ROCKET_FLOOR}、要升的高度烟花撑不住(留下 {@link #ROCKET_FLOOR} 支之外一支升 {@link #RISE_PER_TICK} × {@code rocketBurn} 格)、
     * 升不过去(到了建筑高度的顶)为 null。
     */
    static Airway raise(Craft craft, Plan plan, Vec3 pos) {
        Mobility mobility = craft.mobility();
        if (mobility.fireworks() < ROCKET_FLOOR) {
            return null;
        }
        double dx = plan.aim().x() - pos.x;
        double dz = plan.aim().z() - pos.z;
        double run = Math.hypot(dx, dz);
        if (run < 1) {
            return null;
        }
        double ux = dx / run;
        double uz = dz / run;
        BodyStats stats = craft.snapshot().stats();
        double limit = Math.min(craft.world().getMinY() + craft.world().getHeight() - 2, pos.y + lift(mobility));
        for (double top = Math.max(cruise(craft, pos, ux, uz, run), pos.y + CLIMB_STEP); top <= limit; top += CLIMB_STEP) {
            double height = Math.min(top, limit);
            double climbRun = (height - pos.y) * CLIMB_RUN;
            if (climbRun >= run) {
                // 到目标上空之前升不到这个高度:线会比 CLIMB_RUN 陡,控制器追不上
                return null;
            }
            List<Vec3> corners = List.of(pos, new Vec3(pos.x + ux * climbRun, height, pos.z + uz * climbRun),
                    new Vec3(plan.aim().x(), height, plan.aim().z()));
            // 控制器追不上拉升的线,身体落在线后面一截:扫线时把线往下压 CLIMB_LAG 格,身体落在线下面这么多也过得去
            List<Vec3> lagging = List.of(pos, corners.get(1).subtract(0, CLIMB_LAG, 0), corners.get(2).subtract(0, CLIMB_LAG, 0));
            if (corridor(craft, stats, lagging, ux, uz)) {
                return Airway.through(corners, null);
            }
        }
        return null;
    }
}
