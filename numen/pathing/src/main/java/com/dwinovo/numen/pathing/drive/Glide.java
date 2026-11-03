package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 一次滑翔的计划:从哪起飞、在哪降落、沿什么线飞。滑翔不进格子搜索(转弯半径大、停不下来,状态里得有速度),是行程里的一段({@link Voyage}),
 * 这里是上层:定起飞点、巡航高度与线,下层是局部控制器({@link Pilot},物理 {@link Dynamics#gliding})。叫了 {@code move.fly} 就飞,不和走路比价钱;
 * 飞不起来(缺鞘翅、烟花、耐久、起飞的地方、线、降落的地)在受理时当场拒绝({@link Lack})。
 *
 * <h2>起飞</h2>
 * 一趟飞是:走到起飞点 → 起飞 → 巡航 → 进近降落 → 走完最后一截。起飞点照 MovementSync 的做法:身体附近找一根宽口径、直到巡航高度都空着的柱子
 * ({@link #column}),走过去,在柱子里陡爬升({@link #TAKEOFF_PITCH}),爬到巡航高度再转向目标。候选在附近铺开({@link #takeoffs}:每隔 {@link #TAKEOFF_SPACING} 格一列,
 * 站得住的地面,按离身体的远近从近到远试)。受理只做这几何的检查,不模拟飞行;柱子里贴着柱子爬是局部控制器({@link Pilot})的事。
 * 烟花不够(少于 {@link #ROCKET_FLOOR} 支)或排不出这样的起飞点:走到一处落差够大的边沿,从边沿走出去、离地后展开,只靠高度往下滑({@link #dropping}),没有爬升与巡航。
 *
 * <h2>线</h2>
 * 起飞点→爬到巡航高度→平飞→进近降落({@link #approachCorners})。巡航高度:沿起飞点到目标的直线每 {@link #CRUISE_SAMPLE} 格取一列,看这一列最高的碰撞面
 * ({@link #top}),取其中最高的再加 {@link #CRUISE_MARGIN} 格;线是柱子里竖直爬到这个高度,再平飞。不要求"爬升加按滑翔比直线下滑放得进水平距离":降下来是目标附近的事,高了就在降落点上空盘旋下沉。
 * 没加载的区块是未知,不是空气也不是墙:排线时先当过得去,{@link Sortie} 每刻接着查前方新加载的那段线({@link Helm#blocked}),被挡就抬高巡航重排({@link #raise})或改降;
 * 目标那片没加载时降落点先按起飞点的高度估,那片加载了再定真正的落点({@link #settle})。
 *
 * <h2>降落点</h2>
 * 目标这一侧 {@link #APPROACH} 格处,从近到远一圈一圈找站得住的干地({@link #ground}:一列从高度图往下第一个站得住的格;脚下是液体不要),
 * 目标在水里就落最近的岸。
 *
 * <h2>烟花与耐久</h2>
 * 不要求起飞前备够全程(Baritone):烟花不少于 {@link #ROCKET_FLOOR} 支、鞘翅耐久不少于 {@link #WEAR_FLOOR} 点才起飞,起飞时就不够当场拒绝;飞着少于这个数就近降落
 * ({@link Sortie}),中途落地之后离目标还远、够用就从此刻的位置再起飞({@link Voyage}),不够就收工。
 */
final class Glide {

    /** 烟花少于这么多支就不起飞、飞着就近降落;鞘翅耐久少于这么多点同样(Baritone 的 {@code elytraMinFireworksBeforeLanding} 与 {@code elytraMinimumDurability})。 */
    static final int ROCKET_FLOOR = 5;
    static final int WEAR_FLOOR = 5;
    /** 巡航高度:沿线每隔几格看一列的最高碰撞面,取最高的再加几格(MovementSync 的做法)。 */
    private static final int CRUISE_SAMPLE = 16;
    private static final int CRUISE_MARGIN = 16;
    /** 起飞抬头的俯仰(度):陡抬但不到垂直(REPORT-feel 对起飞的建议),在空柱里爬;爬到巡航高度才转向目标。 */
    static final float TAKEOFF_PITCH = -75.0F;
    /** 抬高巡航重排({@link #raise})的爬升线按这个俯仰算:一格水平一格高。 */
    static final float CLIMB_PITCH = -45.0F;
    /** 滑翔比:每下降一格水平走几格(鞘翅平滑翔大约八九比一)。 */
    static final double GLIDE_RATIO = 9.0;
    /** 线两侧各留几格也要飞得过去。 */
    private static final int SIDE = 2;
    /** 抬高巡航每次多几格。 */
    private static final int CLIMB_STEP = 6;
    /** 降落点要离液体多远(格):落地允许差降落点一圈({@link #TOLERANCE}),这一圈里都得是干地,不然贴着水边的点会落进水里。 */
    private static final int DRY_MARGIN = (int) Glide.TOLERANCE;
    /** 降落点落在目标这一侧多少格处:最后一截落地后走过去。 */
    private static final double APPROACH = 6.0;
    /** 落地离降落点水平不超过这么远就算降到了(演练与实飞同一个判据,飞行线的降落点那一圈就是它)。 */
    static final double TOLERANCE = 6.0;
    /** 落地之后离目标不超过这么远,就是"最后一截":一次计划好的降落落在目标这一侧 {@link #APPROACH} 格处、误差 {@link #TOLERANCE} 格内,走过去就是。 */
    static final double FINAL_WALK = APPROACH + TOLERANCE;
    /**
     * 算力预算(不是物理的判据):起飞点往四周找多少格、每隔几格取一列、最多试几个(每个是一根柱子的扫掠检查);
     * 找边沿往四周看几格、最多试几个;找降落点往四周找多少格。
     */
    private static final int TAKEOFF_RADIUS = 24;
    private static final int TAKEOFF_SPACING = 3;
    private static final int TAKEOFFS_TRIED = 64;
    private static final int EDGE_RADIUS = 16;
    private static final int EDGES_TRIED = 4;
    private static final int LANDING_RADIUS = 24;
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
     * @param landing   降落的格(落地站着的节点);目标那片没加载时是按起飞点高度估的那一格
     * @param way       沿着飞的线,终点是降落点
     * @param blocks    起飞点到目标的水平距离
     * @param aim       这一趟飞往的水平位置
     * @param known     降落点是真的地面;否则那片没加载,降落点是估的,{@link Sortie} 之后那片加载了再定({@link #settle})
     */
    record Plan(boolean drop, BlockPos takeoff, float pitch, BlockPos landing, Airway way, double blocks, Goal.Anchor aim, boolean known) {}

    /** 一次试排:成了是计划,没成是原因。 */
    private record Attempt(Plan plan, Lack.Why why) {

        static Attempt of(Plan plan) {
            return new Attempt(plan, null);
        }

        static Attempt not(Lack.Why why) {
            return new Attempt(null, why);
        }
    }

    /** 降落点:{@code cell} 是真的地面,{@code known} 为 false 时那片没加载,只有水平位置和估的高度。 */
    private record Landing(BlockPos cell, double x, double z, double y, boolean known) {}

    private Glide() {}

    /**
     * 受理:这一趟从这里飞得起来吗。缺鞘翅、耐久、烟花(又没有从边沿滑下去的地方)、起飞点、线、降落的地,都是 {@link Lack};{@code spent} 是这一趟里已经失败过的起飞点,不再用。
     */
    static Admission admit(Rig rig, Goal goal, Set<BlockPos> spent) {
        ServerPlayer body = rig.entity;
        BodySnapshot snapshot = rig.snapshot();
        Mobility mobility = snapshot.mobility();
        if (!mobility.canGlide()) {
            return lacking(rig, Lack.Why.NO_WINGS, 0, 0);
        }
        if (mobility.gliderLife() < WEAR_FLOOR) {
            return lacking(rig, Lack.Why.WORN, WEAR_FLOOR, mobility.gliderLife());
        }
        Goal.Anchor target = goal.anchor(body.position());
        if (target == null) {
            return lacking(rig, Lack.Why.NO_PLACE, 0, 0);
        }
        double range = target.distance(body.position());
        if (range <= FINAL_WALK) {
            return lacking(rig, Lack.Why.TOO_NEAR, (int) FINAL_WALK, (int) Math.round(range));
        }
        BodyStats stats = snapshot.stats();
        Landing land = landing(rig, stats, target, (target.x() - body.getX()) / range, (target.z() - body.getZ()) / range, range, body.getY());
        if (land == null) {
            return lacking(rig, Lack.Why.NO_LANDING, LANDING_RADIUS, 0);
        }
        Plan chosen = null;
        Lack.Why why = Lack.Why.NO_TAKEOFF;
        if (mobility.fireworks() >= ROCKET_FLOOR) {
            Attempt attempt = climbing(rig, stats, target, land, spent);
            chosen = attempt.plan();
            why = attempt.why() != null ? attempt.why() : why;
        }
        if (chosen == null) {
            chosen = dropping(rig, stats, target, spent);
        }
        if (chosen == null) {
            return mobility.fireworks() < ROCKET_FLOOR ? lacking(rig, Lack.Why.TOO_FEW_ROCKETS, ROCKET_FLOOR, mobility.fireworks())
                    : lacking(rig, why, why == Lack.Why.NO_TAKEOFF ? TAKEOFF_RADIUS : 0, 0);
        }
        return Admission.glide(chosen);
    }

    private static Admission lacking(Rig rig, Lack.Why why, int need, int have) {
        Lack lack = new Lack(why, need, have);
        PathLog.debug("{} 飞不起来:{}", rig.who, lack);
        return Admission.lacking(lack);
    }

    /**
     * 有烟花的起飞:附近铺开的起飞点从近到远,取第一个排得成的({@link #flight})。算力预算:最多试 {@link #TAKEOFFS_TRIED} 个起飞点。
     * 没成的原因取最能说明问题的一个:有线过不去({@link Lack.Why#NO_ROUTE})比头顶没开阔({@link Lack.Why#NO_TAKEOFF})更具体。
     */
    private static Attempt climbing(Rig rig, BodyStats stats, Goal.Anchor target, Landing land, Set<BlockPos> spent) {
        Lack.Why last = Lack.Why.NO_TAKEOFF;
        for (BlockPos takeoff : takeoffs(rig, stats, spent)) {
            Attempt attempt = flight(rig, stats, takeoff, target, land);
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
    private static List<BlockPos> takeoffs(Rig rig, BodyStats stats, Set<BlockPos> spent) {
        BlockPos at = rig.entity.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        Stance own = Stance.at(rig.world(), stats, at);
        if (own != null && own.grounded() && !spent.contains(at)) {
            out.add(at);
        }
        List<BlockPos> around = new ArrayList<>();
        for (int x = -TAKEOFF_RADIUS; x <= TAKEOFF_RADIUS; x += TAKEOFF_SPACING) {
            for (int z = -TAKEOFF_RADIUS; z <= TAKEOFF_RADIUS; z += TAKEOFF_SPACING) {
                BlockPos cell = ground(rig, stats, at.getX() + x, at.getZ() + z, at.getY() + TAKEOFF_UP, at.getY() - TAKEOFF_DOWN);
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
     * 柱顶之后的线(巡航与进近,{@link #approachCorners})沿途也扫得过去({@link #corridor})。线是 起飞点 → 柱顶 → 进近的拐点 → 降落点,只排这一次,
     * 检查与飞行读的是同一条。不模拟飞行。
     */
    private static Attempt flight(Rig rig, BodyStats stats, BlockPos takeoff, Goal.Anchor target, Landing land) {
        Vec3 p0 = new Vec3(takeoff.getX() + 0.5, takeoff.getY(), takeoff.getZ() + 0.5);
        double dx = target.x() - p0.x;
        double dz = target.z() - p0.z;
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        double ux = dx / range;
        double uz = dz / range;
        double cruise = cruise(rig, p0, ux, uz, range);
        if (!column(rig, stats, p0, cruise)) {
            return Attempt.not(Lack.Why.NO_TAKEOFF);
        }
        // 柱子里一直爬到巡航高度(沿线最高的碰撞面再加一截,附近的障碍都在它之下),再朝目标平飞
        Vec3 p1 = new Vec3(p0.x, cruise, p0.z);
        Vec3 touchdown = new Vec3(land.x(), land.y(), land.z());
        List<Vec3> tail = approachCorners(p1, touchdown);
        if (tail == null || !corridor(rig, stats, tail, ux, uz)) {
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        List<Vec3> corners = new ArrayList<>();
        corners.add(p0);
        corners.addAll(tail);
        Airway way = Airway.through(corners, touchdown).withZone(TOLERANCE).withClimb(cruise - p0.y);
        BlockPos cell = land.cell() != null ? land.cell() : BlockPos.containing(land.x(), land.y(), land.z());
        return Attempt.of(new Plan(false, takeoff, TAKEOFF_PITCH, cell, way, range, target, land.known()));
    }

    /**
     * 起飞点是一根宽口径的空柱:从脚底到 {@code top} 高度,身体盒扫过去不碰方块,中心与四角各 {@link #SIDE} 格外的五条竖线都是(口径只在 REPORT 里写"宽",
     * 取和线两侧同一个 {@link #SIDE})。没加载的区块读出来是空的,先当空着,飞到时再查({@link Helm#blocked})。
     */
    private static boolean column(Rig rig, BodyStats stats, Vec3 base, double top) {
        Airway.Sweep sweep = Airway.sweep(rig.world(), stats, 0);
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
     * 巡航高度:沿 {@code p0} 朝 {@code (ux, uz)} 到目标的直线,每 {@link #CRUISE_SAMPLE} 格取一列(起点与目标那一列也取),看这一列最高的碰撞面({@link #top}),
     * 取其中最高的再加 {@link #CRUISE_MARGIN} 格;最高到建筑高度的顶。
     */
    private static double cruise(Rig rig, Vec3 p0, double ux, double uz, double range) {
        double highest = p0.y;
        for (double along = 0; ; along += CRUISE_SAMPLE) {
            double s = Math.min(along, range);
            highest = Math.max(highest, top(rig, (int) Math.floor(p0.x + ux * s), (int) Math.floor(p0.z + uz * s)));
            if (s >= range) {
                break;
            }
        }
        return Math.min(highest + CRUISE_MARGIN, rig.world().getMinBuildHeight() + rig.world().getHeight() - 2);
    }

    /** 这一列最高的碰撞面(站在上面脚的高度):高度图里第一个挡动作或有流体的方块之上;没加载为建筑高度的底。 */
    static int top(Rig rig, int x, int z) {
        LiveWorld world = rig.world();
        return world.isLoaded(x, z) ? world.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : world.getMinBuildHeight();
    }

    /**
     * 从边沿走出去的计划:身体附近落差大的边沿(那一格站得住,朝目标走过去的前面没有地面),落差大的先试,最多试 {@link #EDGES_TRIED} 个;
     * 线是边沿到降落点的直线下滑线,降落点离边沿不超过滑翔比的 {@link #GLIDE_SHARE}。没有烟花时落点够不够得着全靠已知的地面,所以降落点必须是真的
     * (那片没加载的不排)。没有为 null。
     */
    private static Plan dropping(Rig rig, BodyStats stats, Goal.Anchor target, Set<BlockPos> spent) {
        BlockPos at = rig.entity.blockPosition();
        double dx = target.x() - rig.entity.getX();
        double dz = target.z() - rig.entity.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1) {
            return null;
        }
        int ax = (int) Math.round(dx / length * 2);
        int az = (int) Math.round(dz / length * 2);
        int floorY = rig.world().getMinBuildHeight();
        record Edge(BlockPos cell, int drop) {}
        List<Edge> edges = new ArrayList<>();
        for (int x = -EDGE_RADIUS; x <= EDGE_RADIUS; x++) {
            for (int z = -EDGE_RADIUS; z <= EDGE_RADIUS; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos cell = at.offset(x, y, z);
                    Stance stance = Stance.at(rig.world(), stats, cell);
                    if (stance == null || !stance.grounded() || spent.contains(cell)) {
                        continue;
                    }
                    BlockPos beyond = cell.offset(ax, 0, az);
                    Stance ahead = Stance.at(rig.world(), stats, beyond);
                    if (ahead == null || !ahead.grounded()) {
                        BlockPos floor = ground(rig, stats, beyond.getX(), beyond.getZ(), cell.getY(), floorY);
                        edges.add(new Edge(cell, cell.getY() - (floor == null ? floorY : floor.getY())));
                    }
                }
            }
        }
        edges.sort(Comparator.comparingInt((Edge e) -> -e.drop()).thenComparingDouble(e -> e.cell().distSqr(at)));
        for (Edge edge : edges.subList(0, Math.min(edges.size(), EDGES_TRIED))) {
            Plan plan = fromEdge(rig, stats, edge.cell(), target);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Plan fromEdge(Rig rig, BodyStats stats, BlockPos edge, Goal.Anchor target) {
        double dx = target.x() - (edge.getX() + 0.5);
        double dz = target.z() - (edge.getZ() + 0.5);
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return null;
        }
        double ux = dx / range;
        double uz = dz / range;
        double y0 = edge.getY();
        // 降落点:沿线离边沿 d 格的地面;d 受高度限制(高度要够滑过去),也不越过目标这一侧的 APPROACH 格。没有烟花只能往下滑,地面从边沿的高度往下找
        double d = Math.max(1, range - APPROACH);
        BlockPos landing = null;
        for (int round = 0; round < 3; round++) {
            landing = ground(rig, stats, (int) Math.floor(edge.getX() + 0.5 + ux * d), (int) Math.floor(edge.getZ() + 0.5 + uz * d), edge.getY(),
                    rig.world().getMinBuildHeight());
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
        if (!corridor(rig, stats, List.of(p0.add(ux * 1.2, 0.2, uz * 1.2), touchdown), ux, uz)) {
            return null;
        }
        Airway way = Airway.through(List.of(p0, touchdown), touchdown).withZone(TOLERANCE);
        return new Plan(true, edge, 0, landing, way, Math.hypot(landing.getX() - edge.getX(), landing.getZ() - edge.getZ()), target, true);
    }

    /** 沿 {@code corners} 这条折线飞,线上和线两侧各 {@link #SIDE} 格的平行线都扫得过去(身体盒不碰方块)。没加载的区块读出来是空的:先当过得去,飞到时再查。 */
    private static boolean corridor(Rig rig, BodyStats stats, List<Vec3> corners, double ux, double uz) {
        Airway.Sweep sweep = Airway.sweep(rig.world(), stats, 0);
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
     * 降落点:目标这一侧 {@link #APPROACH} 格处,从近到远一圈一圈找站得住的干地({@link #ground},每一列从高度图起往下找),第一圈有就停,最多 {@link #LANDING_RADIUS} 圈;
     * 目标在水里,就落到最近的岸。那片还没加载:降落点只有水平位置,高度按 {@code assumed} 估,{@code known} 为 false。加载了却找不到站得住的干地为 null。
     */
    private static Landing landing(Rig rig, BodyStats stats, Goal.Anchor aim, double ux, double uz, double range, double assumed) {
        double back = Math.min(APPROACH, range / 4);
        int cx = (int) Math.floor(aim.x() - ux * back);
        int cz = (int) Math.floor(aim.z() - uz * back);
        LiveWorld world = rig.world();
        if (!world.isLoaded(cx, cz) || !world.isLoaded(cx - LANDING_RADIUS, cz - LANDING_RADIUS) || !world.isLoaded(cx + LANDING_RADIUS, cz + LANDING_RADIUS)) {
            return new Landing(null, cx + 0.5, cz + 0.5, assumed, false);
        }
        for (int r = 0; r <= LANDING_RADIUS; r++) {
            BlockPos best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) {
                        continue;
                    }
                    double d = x * x + z * z;
                    BlockPos cell = d < bestDistance ? ground(rig, stats, cx + x, cz + z, top(rig, cx + x, cz + z), world.getMinBuildHeight()) : null;
                    if (cell != null && dry(rig, cell)) {
                        best = cell;
                        bestDistance = d;
                    }
                }
            }
            if (best != null) {
                return new Landing(best, best.getX() + 0.5, best.getZ() + 0.5, best.getY(), true);
            }
        }
        return null;
    }

    /** 降落点四周 {@link #DRY_MARGIN} 格内脚下和脚下一层都不是液体:落地允许差降落点一圈,这一圈里不能有水。 */
    private static boolean dry(Rig rig, BlockPos cell) {
        for (int dx = -DRY_MARGIN; dx <= DRY_MARGIN; dx++) {
            for (int dz = -DRY_MARGIN; dz <= DRY_MARGIN; dz++) {
                BlockPos around = cell.offset(dx, 0, dz);
                if (!rig.world().getFluidState(around).isEmpty() || !rig.world().getFluidState(around.below()).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 这一列从 {@code from} 往下到 {@code to} 第一个站得住的干地格(地面);没有为 null。往下遇到的第一个落脚处是液体(脚或头在水里、岩浆里)就是没有:
     * 降落点不在液体里,也不穿过水面去找水底。
     */
    static BlockPos ground(Rig rig, BodyStats stats, int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockPos cell = new BlockPos(x, y, z);
            if (!rig.world().getFluidState(cell).isEmpty()) {
                return null;
            }
            Stance stance = Stance.at(rig.world(), stats, cell);
            if (stance != null && stance.grounded()) {
                return rig.world().getFluidState(cell.above()).isEmpty() ? cell : null;
            }
        }
        return null;
    }

    // ==================== 边飞边看 ====================

    /**
     * 降落点还没定(目标那片没加载)的飞行:那片加载了,就在那片定真正的降落点,按此刻的位置重排一条往它去的线;
     * 那片还没加载、或排不出为 null。
     */
    static Airway settle(Rig rig, Plan plan, Vec3 pos) {
        if (plan.known()) {
            return null;
        }
        double dx = plan.aim().x() - pos.x;
        double dz = plan.aim().z() - pos.z;
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return null;
        }
        BodyStats stats = rig.snapshot().stats();
        Landing land = landing(rig, stats, plan.aim(), dx / range, dz / range, range, plan.takeoff().getY());
        if (land == null || !land.known()) {
            return null;
        }
        return approach(rig, stats, pos, new Vec3(land.x(), land.y(), land.z()));
    }

    /**
     * 从空中的 {@code pos} 往降落点 {@code touchdown} 去的线({@link #approachCorners}):线两侧扫不过去为 null。
     */
    static Airway approach(Rig rig, BodyStats stats, Vec3 pos, Vec3 touchdown) {
        List<Vec3> corners = approachCorners(pos, touchdown);
        if (corners == null) {
            return null;
        }
        double run = Math.hypot(touchdown.x - pos.x, touchdown.z - pos.z);
        return corridor(rig, stats, corners, (touchdown.x - pos.x) / run, (touchdown.z - pos.z) / run)
                ? Airway.through(corners, touchdown).withZone(TOLERANCE) : null;
    }

    /** 盘旋下降的圈半径(格),和每一圈取几条边。 */
    private static final double SPIRAL_RADIUS = 14.0;
    private static final int SPIRAL_SIDES = 12;
    /** 盘旋下降比滑翔比陡一点(只取滑翔比的这个比例):比自然下滑沉得快,不靠烟花也跟得上。 */
    private static final double SPIRAL_SHARE = 0.8;

    /**
     * 进近的线上的拐点({@code pos} 是第一个),贴在降落点上为 null。高度够用(按滑翔比下滑刚好落在降落点放得进水平距离里):{@code pos}、平飞到按滑翔比下滑的起点、降落点。
     * 高得下不去(巡航高度比降落点高得多,或离得太近):不朝降落点硬俯冲,而是平飞到降落点上空,绕着它盘旋着下降
     * (每圈沉的高度按比滑翔比陡一点的下滑算),降到按滑翔比能直接落到降落点了,再朝它下滑。
     */
    static List<Vec3> approachCorners(Vec3 pos, Vec3 touchdown) {
        double dx = touchdown.x - pos.x;
        double dz = touchdown.z - pos.z;
        double run = Math.hypot(dx, dz);
        if (run < 1) {
            return null;
        }
        double descent = (pos.y - touchdown.y) * GLIDE_RATIO;
        if (descent < run) {
            return List.of(pos, new Vec3(touchdown.x - dx / run * descent, pos.y, touchdown.z - dz / run * descent), touchdown);
        }
        // 高得下不去:到圈上离身体最近的一点,平飞过去,再一边绕一边沉
        double angle = Math.atan2(-dz, -dx);
        List<Vec3> corners = new ArrayList<>();
        corners.add(pos);
        double y = pos.y;
        double finalDrop = SPIRAL_RADIUS / (GLIDE_RATIO * SPIRAL_SHARE) + 1;
        double sideDrop = 2 * Math.PI * SPIRAL_RADIUS / SPIRAL_SIDES / (GLIDE_RATIO * SPIRAL_SHARE);
        while (true) {
            corners.add(new Vec3(touchdown.x + SPIRAL_RADIUS * Math.cos(angle), y, touchdown.z + SPIRAL_RADIUS * Math.sin(angle)));
            if (y - touchdown.y <= finalDrop) {
                break;
            }
            angle += 2 * Math.PI / SPIRAL_SIDES;
            y = Math.max(touchdown.y + finalDrop - 1, y - sideDrop);
        }
        corners.add(touchdown);
        return corners;
    }

    /**
     * 前方被挡:从空中的 {@code pos} 抬高巡航重排一条线(再爬一段,过得去再进近到原来的降落点),沿途扫得过去才算;
     * 烟花少于 {@link #ROCKET_FLOOR}、爬不过去(到了建筑高度的顶)为 null。
     */
    static Airway raise(Rig rig, Airway current, Vec3 pos) {
        Mobility mobility = Snapshots.mobility(rig.entity);
        Vec3 touchdown = current.touchdown();
        if (touchdown == null || mobility.fireworks() < ROCKET_FLOOR) {
            return null;
        }
        double dx = touchdown.x - pos.x;
        double dz = touchdown.z - pos.z;
        double run = Math.hypot(dx, dz);
        if (run < 1) {
            return null;
        }
        double ux = dx / run;
        double uz = dz / run;
        BodyStats stats = rig.snapshot().stats();
        int top = rig.world().getMinBuildHeight() + rig.world().getHeight() - 2;
        double slope = Math.tan(Math.toRadians(-CLIMB_PITCH));
        for (int climb = CLIMB_STEP; pos.y + climb <= top; climb += CLIMB_STEP) {
            double cruise = pos.y + climb;
            double along = climb / slope;
            if (along + APPROACH > run) {
                return null;
            }
            Vec3 p1 = new Vec3(pos.x + ux * along, cruise, pos.z + uz * along);
            List<Vec3> tail = approachCorners(p1, touchdown);
            if (tail == null) {
                return null;
            }
            List<Vec3> corners = new ArrayList<>();
            corners.add(pos);
            corners.addAll(tail);
            if (corridor(rig, stats, corners, ux, uz)) {
                return Airway.through(corners, touchdown).withZone(TOLERANCE);
            }
        }
        return null;
    }
}
