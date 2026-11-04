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
 * ({@link Terrain#top}),取其中最高的再加 {@link #CRUISE_MARGIN} 格;线是柱子里竖直爬到这个高度,再朝目标飞,进入目标 {@link #DESCENT_RANGE} 格之前把多余的高度放掉({@link #finale}),
 * 进去时身体离地只剩盘旋不到一圈沉得掉的高度。
 * 没加载的区块是未知,不是空气也不是墙:排线时先当过得去,{@link Sortie} 每刻接着查前方新加载的那段线({@link Helm#blocked}),被挡就沿航线往前拉升重排({@link #raise})或改降。
 *
 * <h2>降落</h2>
 * 水平进入目标 {@link #DESCENT_RANGE} 格({@link Sortie})才选落点,落点只求安全({@link Landings}),不对准确切的格、不为离目标近而挑点:
 * 先找飞行方向前面一路飞得过去的(俯冲、拉平、下滑,不转圈),由近到远取第一个滑得到的({@link #descent});这样的一个也没有才绕着落点盘旋下沉({@link #descents}),
 * 进入目标范围时的高度不到盘旋一圈({@link #finale})。
 * 降落途中落点落不下了,换下一个安全的。
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
    /** 进目标范围前放掉巡航高度:每下降一格水平走几格(比滑翔比陡一点,不靠烟花也跟得上);沿最后这一程取地面高度的间距。 */
    private static final double CRUISE_SINK = GLIDE_RATIO * 0.8;
    private static final int FINALE_SAMPLE = 4;
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

    /** 降落的结论:落点,和从此刻的位置到它的线。 */
    record Descent(BlockPos spot, Airway way) {}

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
        // 柱子里一直爬到巡航高度(沿线最高的碰撞面再加一截,附近的障碍都在它之下),再朝目标飞;最后一程把多余的高度放掉({@link #finale})
        Vec3 p1 = new Vec3(p0.x, cruise, p0.z);
        // 先试把高度提前放掉的线({@link #finale}),沿途有山脊、树冠挡着(它们高过下滑线)就试平飞到目标上空的线
        List<Vec3> level = List.of(p1, new Vec3(target.x(), cruise, target.z()));
        List<Vec3> corners = finale(craft, p1, target, ux, uz, range);
        if (!corridor(craft, stats, corners, ux, uz)) {
            corners = level;
            if (!corridor(craft, stats, corners, ux, uz)) {
                return Attempt.not(Lack.Why.NO_ROUTE);
            }
        }
        List<Vec3> line = new ArrayList<>(corners);
        line.add(0, p0);
        Airway way = Airway.through(line, null).withClimb(cruise - p0.y);
        return Attempt.of(new Plan(false, takeoff, TAKEOFF_PITCH, null, way, range, target));
    }

    /**
     * 从柱顶 {@code p1}(巡航高度)朝目标的折线({@code p1} 是第一个点)。进目标 {@link #DESCENT_RANGE} 格才选落点,进去时身体要已经够低,不然只能原地盘旋着沉:
     * 在这之前就按下滑比({@link #CRUISE_SINK})把巡航高度放掉,到进入点({@code E},目标这一侧 {@link #DESCENT_RANGE} 格)时只比最后这一程地面的最高处高 {@link #APPROACH_HEIGHT} 格
     * (大圈盘旋四分之三圈沉的高度加最后下滑的那一截,{@link #spiral}),再平飞到目标上空。柱顶到 {@code E} 不够放这么多高度(水平距离不到下滑比要的)就从柱顶直接往下滑,线的坡度不陡过 {@link #DIVE_RATIO}。
     * 进入点那一带没加载(看不到地面)就不预先放高度,线照旧平飞到目标上空。
     */
    private static List<Vec3> finale(Craft craft, Vec3 p1, Goal.Anchor target, double ux, double uz, double range) {
        Vec3 over = new Vec3(target.x(), p1.y, target.z());
        double toEntry = range - DESCENT_RANGE;
        double ex = target.x() - ux * DESCENT_RANGE;
        double ez = target.z() - uz * DESCENT_RANGE;
        if (toEntry < 1 || !craft.world().isLoaded((int) Math.floor(ex), (int) Math.floor(ez)) || !craft.world().isLoaded((int) Math.floor(target.x()), (int) Math.floor(target.z()))) {
            return List.of(p1, over);
        }
        double ground = Double.NEGATIVE_INFINITY;
        for (double along = 0; ; along += FINALE_SAMPLE) {
            double s = Math.min(along, DESCENT_RANGE);
            ground = Math.max(ground, craft.world().top((int) Math.floor(ex + ux * s), (int) Math.floor(ez + uz * s)));
            if (s >= DESCENT_RANGE) {
                break;
            }
        }
        double high = Math.max(ground + APPROACH_HEIGHT, p1.y - toEntry / DIVE_RATIO);
        if (high >= p1.y) {
            return List.of(p1, over);
        }
        Vec3 entry = new Vec3(ex, high, ez);
        Vec3 end = new Vec3(target.x(), high, target.z());
        double sink = (p1.y - high) * CRUISE_SINK;
        if (sink < toEntry) {
            return List.of(p1, new Vec3(ex - ux * sink, p1.y, ez - uz * sink), entry, end);
        }
        return List.of(p1, entry, end);
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
        return Math.min(highest + CRUISE_MARGIN, craft.world().getMinBuildHeight() + craft.world().getHeight() - 2);
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
        int floorY = craft.world().getMinBuildHeight();
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

    /**
     * 降落:从空中的 {@code pos} 起,先看正下方那一列,再由近到远({@link Landings#nearest}),取第一个<b>滑得到</b>({@link #reaches},有烟花算上烟花)、
     * 线沿途扫得过去({@link #approachCorners})的安全落点,和往它去的线。{@code barred} 是这一段里落不下来、拉黑了的落点。不对准确切的格,也不为离目标近而挑点。
     * 算力预算:最多扫 {@link #LINES_TRIED} 个落点的线。没有为 null。
     */
    static Descent descent(Craft craft, Vec3 pos, Mobility mobility, Set<BlockPos> barred) {
        BodyStats stats = craft.snapshot().stats();
        double lift = lift(mobility);
        Vec3 heading = heading(craft);
        int x = (int) Math.floor(pos.x);
        int z = (int) Math.floor(pos.z);
        // 先找一路飞过去、不用转圈的(飞行方向前面的落点,俯冲加拉平或直接下滑,冲过落点的一段也是地面);没有才轮到绕着落点盘旋
        for (boolean circling : new boolean[] {false, true}) {
            Descent found = Landings.nearest(craft, stats, x, z, LINES_TRIED, spot -> {
                if (barred.contains(spot)) {
                    return null;
                }
                Vec3 touchdown = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                if (!reaches(pos, touchdown, lift) || !circling && !straight(craft, stats, pos, spot, heading)) {
                    return null;
                }
                List<Vec3> corners = approachCorners(craft, stats, pos, touchdown, heading, circling);
                return corners == null ? null : new Descent(spot, Airway.through(corners, touchdown).withZone(TOLERANCE));
            });
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 落点在身体飞行方向的前面({@link #AHEAD} 度之内),冲过落点之后还有一段安全的地面({@link Landings#runway}):一路飞过去、不用掉头。 */
    private static boolean straight(Craft craft, BodyStats stats, Vec3 pos, BlockPos spot, Vec3 heading) {
        double dx = spot.getX() + 0.5 - pos.x;
        double dz = spot.getZ() + 0.5 - pos.z;
        double run = Math.hypot(dx, dz);
        return run >= 1 && (dx * heading.x + dz * heading.z) / run >= Math.cos(Math.toRadians(AHEAD))
                && Landings.runway(craft, stats, spot, dx / run, dz / run);
    }

    /** 不转圈的落点要在飞行方向左右各多少度之内。 */
    private static final double AHEAD = 40.0;

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

    /** 盘旋下降的圈半径(格),大圈在前、小圈在后(落点四周挤着树冠、山坡,大圈放不下才换小圈);和每一圈取几条边。 */
    private static final double[] SPIRAL_RADII = {14.0, 8.0};
    private static final int SPIRAL_SIDES = 12;
    /** 盘旋的圈内外各留几格(身体转弯时落在圈外一截)。 */
    private static final double SPIRAL_MARGIN = 3.0;
    /** 盘旋下降比滑翔比陡一点(只取滑翔比的这个比例):比自然下滑沉得快,不靠烟花也跟得上。 */
    private static final double SPIRAL_SHARE = 0.8;
    /** 进目标范围时离地多高(格):大圈盘旋四分之三圈沉的高度加最后朝落点下滑的那一截({@link #spiral} 的算法)。不到一整圈,盘旋的圈数才少。 */
    private static final double APPROACH_HEIGHT = 0.75 * 2 * Math.PI * SPIRAL_RADII[0] / (GLIDE_RATIO * SPIRAL_SHARE) + SPIRAL_RADII[0] / (GLIDE_RATIO * SPIRAL_SHARE) + 1;
    /**
     * 进近的俯冲:每下降一格水平走几格。量到的滑翔(每刻水平 1.6 到 1.7 格):10 度那一档竖直每刻 -0.3 上下(约 5.5 比 1),22 度 -0.6 上下,27 度以上 -0.7。
     * 竖直速度在每刻 -0.5 之内,原版的坠落距离才每刻清零({@link Dynamics#SLOW_FALL}),所以俯冲取 5 比 1,正好是 10 度那一档的下滑比;
     * 控制器追线追过头(22 度以上)的那几刻坠落距离会往上攒,由降落点前的拉平段收回来。
     */
    private static final double DIVE_RATIO = 5.0;
    /** 水平距离不够放下 {@link #DIVE_RATIO} 的俯冲时,从 {@code pos} 起直接俯冲,最陡到这个下滑比(再陡控制器追不上线,落地落得重)。 */
    static final double STEEPEST_RATIO = 4.0;
    /** 拉平:降落点前最后这么多格水平,按滑翔比平缓地下滑,到地时竖直速度远在 -0.5 之上。 */
    private static final double FLARE_RUN = 24.0;

    /**
     * 进近的线的几种排法,由简到繁({@code pos} 是每条的第一个拐点,贴在降落点上为空表):
     * <ol>
     *   <li>高度刚好按滑翔比放得进水平距离里:{@code pos}、平飞到按滑翔比下滑的起点、降落点;</li>
     *   <li>高了一些:平飞到俯冲的起点,按 {@link #DIVE_RATIO} 俯冲掉高度,{@link #FLARE_RUN} 格外拉平,再按滑翔比滑到降落点;水平距离不够这样放,就从 {@code pos} 起直接俯冲,俯冲的下滑比在 {@link #STEEPEST_RATIO} 与 {@link #DIVE_RATIO} 之间;</li>
     *   <li>最陡的俯冲也放不进水平距离里:平飞到降落点上空,绕着它盘旋着下降(每圈沉的高度按比滑翔比陡一点的下滑算),
     *       降到按滑翔比能直接落到降落点了,再朝它下滑。排在俯冲之后,是俯冲那一路有遮挡时的替补;圈有大小两种({@link #SPIRAL_RADII}),大的放不下换小的;
     *       落点就在正下方(没有水平距离可滑)只有这一种,从圈上身体飞行方向({@code heading})那一侧的点转起,不掉头。</li>
     * </ol>
     */
    static List<List<Vec3>> descents(Vec3 pos, Vec3 touchdown, Vec3 heading) {
        double dx = touchdown.x - pos.x;
        double dz = touchdown.z - pos.z;
        double run = Math.hypot(dx, dz);
        if (run < 1) {
            return spirals(pos, touchdown, -heading.x, -heading.z);
        }
        double ux = dx / run;
        double uz = dz / run;
        double height = pos.y - touchdown.y;
        if (height * GLIDE_RATIO < run) {
            return List.of(List.of(pos, new Vec3(touchdown.x - ux * height * GLIDE_RATIO, pos.y, touchdown.z - uz * height * GLIDE_RATIO), touchdown));
        }
        List<List<Vec3>> options = new ArrayList<>();
        double flareHeight = FLARE_RUN / GLIDE_RATIO;
        double diveHeight = height - flareHeight;
        double room = run - FLARE_RUN;
        if (diveHeight >= 0 && room >= diveHeight * STEEPEST_RATIO) {
            Vec3 flare = new Vec3(touchdown.x - ux * FLARE_RUN, touchdown.y + flareHeight, touchdown.z - uz * FLARE_RUN);
            List<Vec3> line = new ArrayList<>(List.of(pos));
            double diveRun = diveHeight * DIVE_RATIO;
            if (diveRun < room) {
                line.add(new Vec3(flare.x - ux * diveRun, pos.y, flare.z - uz * diveRun));
            }
            line.add(flare);
            line.add(touchdown);
            options.add(line);
        }
        options.addAll(spirals(pos, touchdown, dx, dz));
        return options;
    }

    /**
     * 从 {@code pos} 往降落点 {@code touchdown} 去的进近的线({@link #descents} 里沿途扫得过去的第一种,{@link #corridor});都过不去为 null。{@code heading} 是身体水平飞的方向。
     * 盘旋只在俯冲放不下或俯冲那一路有遮挡时才轮到;{@code circling} 为假时不排盘旋。
     */
    static List<Vec3> approachCorners(Craft craft, BodyStats stats, Vec3 pos, Vec3 touchdown, Vec3 heading, boolean circling) {
        double run = Math.hypot(touchdown.x - pos.x, touchdown.z - pos.z);
        double ux = run < 1 ? heading.x : (touchdown.x - pos.x) / run;
        double uz = run < 1 ? heading.z : (touchdown.z - pos.z) / run;
        for (List<Vec3> corners : descents(pos, touchdown, heading)) {
            if (!circling && corners.size() >= 3 && isSpiral(corners.get(1), touchdown)) {
                continue;
            }
            if (corridor(craft, stats, corners, ux, uz) && roomAround(craft, stats, corners, touchdown, ux, uz)) {
                return corners;
            }
        }
        return null;
    }

    /**
     * 盘旋的圈除了线上、线两侧扫得过去,圈的内外各 {@link #SPIRAL_MARGIN} 格也要过得去:盘旋转弯时身体落在圈外一截、擦着圈边的树冠与山坡就是撞上去掉血。
     * 别的线(平飞、俯冲)不看。圈上的拐点离降落点的水平距离都等于圈半径({@link #SPIRAL_RADII}),靠这个认出盘旋。
     */
    private static boolean roomAround(Craft craft, BodyStats stats, List<Vec3> corners, Vec3 touchdown, double ux, double uz) {
        if (corners.size() < 3 || !isSpiral(corners.get(1), touchdown)) {
            return true;
        }
        for (double margin : new double[] {SPIRAL_MARGIN, -SPIRAL_MARGIN}) {
            List<Vec3> wide = new ArrayList<>(corners);
            for (int i = 1; i < wide.size() - 1; i++) {
                Vec3 c = wide.get(i);
                double dx = c.x - touchdown.x;
                double dz = c.z - touchdown.z;
                double r = Math.hypot(dx, dz);
                wide.set(i, new Vec3(touchdown.x + dx * (r + margin) / r, c.y, touchdown.z + dz * (r + margin) / r));
            }
            if (!corridor(craft, stats, wide, ux, uz)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSpiral(Vec3 ringPoint, Vec3 touchdown) {
        double r = Math.hypot(ringPoint.x - touchdown.x, ringPoint.z - touchdown.z);
        for (double radius : SPIRAL_RADII) {
            if (Math.abs(r - radius) < 1.0E-6) {
                return true;
            }
        }
        return false;
    }

    /** 绕降落点盘旋着下降的几种圈(大的在前)。 */
    private static List<List<Vec3>> spirals(Vec3 pos, Vec3 touchdown, double dx, double dz) {
        List<List<Vec3>> out = new ArrayList<>();
        for (double radius : SPIRAL_RADII) {
            out.add(spiral(pos, touchdown, dx, dz, radius));
        }
        return out;
    }

    /** 绕降落点盘旋着下降的拐点:到圈上离身体最近的一点,平飞过去,再一边绕一边沉,最后朝降落点下滑。 */
    private static List<Vec3> spiral(Vec3 pos, Vec3 touchdown, double dx, double dz, double radius) {
        double angle = Math.atan2(-dz, -dx);
        List<Vec3> corners = new ArrayList<>();
        corners.add(pos);
        double y = pos.y;
        double finalDrop = radius / (GLIDE_RATIO * SPIRAL_SHARE) + 1;
        double sideDrop = 2 * Math.PI * radius / SPIRAL_SIDES / (GLIDE_RATIO * SPIRAL_SHARE);
        while (true) {
            corners.add(new Vec3(touchdown.x + radius * Math.cos(angle), y, touchdown.z + radius * Math.sin(angle)));
            if (y - touchdown.y <= finalDrop) {
                break;
            }
            angle += 2 * Math.PI / SPIRAL_SIDES;
            y = Math.max(touchdown.y + finalDrop - 1, y - sideDrop);
        }
        corners.add(touchdown);
        return corners;
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
        double limit = Math.min(craft.world().getMinBuildHeight() + craft.world().getHeight() - 2, pos.y + lift(mobility));
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
