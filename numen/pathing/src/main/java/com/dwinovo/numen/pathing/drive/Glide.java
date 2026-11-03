package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

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
 * 一趟飞是:走到起飞点 → 起飞 → 巡航 → 进近降落 → 走完最后一截。起飞点在身体附近铺开找({@link #takeoffs}:每隔 {@link #TAKEOFF_SPACING} 格一列,
 * 站得住的地面,按离身体的远近从近到远试),不是只取脚边几格。一处起飞点能不能用,看实飞的那个局部控制器在<b>活世界</b>里从这里起跳、抬头爬升能不能躲开沿途的方块
 * ({@link #flight}:先只模拟爬升那一段,通了再整条飞一遍),不是一条固定角度的通道;抬头的俯仰从低到高试({@link #PITCHES}),取第一个飞得通的。
 * 模拟的轨迹就是实飞的轨迹(同一个控制器、同一个扫路判据),计划里爬升的那一段线和交给飞行的线是同一条({@code way}),只排一次。有烟花(不少于 {@link #ROCKET_FLOOR} 支)就这样起飞;
 * 烟花不够,或附近排不出这样的起飞点:走到一处落差够大的边沿,从边沿走出去、离地后展开,只靠高度往下滑({@link #dropping}),没有爬升与巡航。
 *
 * <h2>线</h2>
 * 起飞点→爬到巡航高度→平飞→进近降落({@link #approachCorners})。巡航高度:沿起飞点到目标的直线每 {@link #CRUISE_SAMPLE} 格取一列,看这一列最高的碰撞面
 * ({@link #top}),取其中最高的再加 {@link #CRUISE_MARGIN} 格。不要求"爬升加按滑翔比直线下滑放得进水平距离":降下来是目标附近的事,高了就在降落点上空盘旋下沉。
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
    /** 起飞抬头的俯仰(度),从低到高试:陡抬但不到垂直,能爬上巡航高度的第一个。 */
    private static final float[] PITCHES = {-45.0F, -60.0F, -75.0F};
    /** 抬高巡航重排({@link #raise})的爬升线按这个俯仰算:一格水平一格高。 */
    static final float CLIMB_PITCH = -45.0F;
    /** 滑翔比:每下降一格水平走几格(鞘翅平滑翔大约八九比一)。 */
    static final double GLIDE_RATIO = 9.0;
    /** 线两侧各留几格也要飞得过去。 */
    private static final int SIDE = 2;
    /** 抬高巡航每次多几格。 */
    private static final int CLIMB_STEP = 6;
    /** 降落点落在目标这一侧多少格处:最后一截落地后走过去。 */
    private static final double APPROACH = 6.0;
    /** 落地离降落点水平不超过这么远就算降到了(演练与实飞同一个判据,飞行线的降落点那一圈就是它)。 */
    static final double TOLERANCE = 6.0;
    /** 落地之后离目标不超过这么远,就是"最后一截":一次计划好的降落落在目标这一侧 {@link #APPROACH} 格处、误差 {@link #TOLERANCE} 格内,走过去就是。 */
    static final double FINAL_WALK = APPROACH + TOLERANCE;
    /**
     * 算力预算(不是物理的判据):起飞点往四周找多少格、每隔几格取一列、最多试几个——每试一个起飞点要做一次爬升的模拟;
     * 找边沿往四周看几格、最多试几个;找降落点往四周找多少格。
     */
    private static final int TAKEOFF_RADIUS = 24;
    private static final int TAKEOFF_SPACING = 3;
    private static final int TAKEOFFS_TRIED = 12;
    private static final int EDGE_RADIUS = 16;
    private static final int EDGES_TRIED = 4;
    private static final int LANDING_RADIUS = 24;
    /** 起飞点从身体脚下往上、往下各看几层地面(走得过去的高低)。 */
    private static final int TAKEOFF_UP = 3;
    private static final int TAKEOFF_DOWN = 6;
    /** 没有烟花时下滑线能飞多远(滑翔比的这个比例,留余量)。 */
    private static final double GLIDE_SHARE = 0.65;
    /**
     * 模拟最多飞几刻(算力预算):爬升那一段 {@link #CLIMB_LIMIT};整条线是 {@link #SIM_BASE} 刻加每格线长 {@link #SIM_PER_BLOCK} 刻,封顶 {@link #REHEARSAL}——
     * 控制器平均每刻飞不到一格,飞了这么久还没到的线是飞不到的,不必真飞满一千多刻才发现。
     */
    private static final int CLIMB_LIMIT = 400;
    private static final int REHEARSAL = 1200;
    private static final int SIM_BASE = 100;
    private static final double SIM_PER_BLOCK = 2.0;
    /** 模拟时烟花不设限:检查的是线的形状飞不飞得过去,够不够用是飞着看的({@link #ROCKET_FLOOR})。 */
    private static final int UNLIMITED = 1000;

    /**
     * 一份滑翔计划。
     *
     * @param drop      从边沿走出去起飞(不起跳、不爬升);否则平地起跳
     * @param takeoff   起飞的格(站着的节点)
     * @param pitch     起跳后抬头的俯仰(度);从边沿走出去时是 0
     * @param landing   降落的格(落地站着的节点);目标那片没加载时是按起飞点高度估的那一格
     * @param way       沿着飞的线,终点是降落点
     * @param ticks     估计飞行用几刻(模拟)
     * @param rockets   估计用几支烟花(模拟)
     * @param blocks    起飞点到目标的水平距离
     * @param aim       这一趟飞往的水平位置
     * @param known     降落点是真的地面;否则那片没加载,降落点是估的,{@link Sortie} 之后那片加载了再定({@link #settle})
     */
    record Plan(boolean drop, BlockPos takeoff, float pitch, BlockPos landing, Airway way, int ticks, int rockets, double blocks,
                Goal.Anchor aim, boolean known) {

        /** 估计鞘翅耗几点耐久(滑翔每秒耗一点)。 */
        int wear() {
            return ticks / 20 + 1;
        }
    }

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
            Attempt attempt = climbing(rig, snapshot, stats, target, land, spent);
            chosen = attempt.plan();
            why = attempt.why() != null ? attempt.why() : why;
        }
        if (chosen == null) {
            chosen = dropping(rig, snapshot, stats, target, spent);
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
     * 有烟花的起飞:附近铺开的起飞点从近到远,每个试几档抬头的俯仰({@link #PITCHES}),取第一个排得成的。算力预算:最多试 {@link #TAKEOFFS_TRIED} 个起飞点。
     * 没成的原因取最能说明问题的一个:有线过不去({@link Lack.Why#NO_ROUTE})比头顶没开阔({@link Lack.Why#NO_TAKEOFF})更具体。
     */
    private static Attempt climbing(Rig rig, BodySnapshot snapshot, BodyStats stats, Goal.Anchor target, Landing land, Set<BlockPos> spent) {
        Lack.Why last = Lack.Why.NO_TAKEOFF;
        for (BlockPos takeoff : takeoffs(rig, stats, spent)) {
            for (float pitch : PITCHES) {
                Attempt attempt = flight(rig, snapshot, stats, takeoff, pitch, target, land);
                if (attempt.plan() != null) {
                    return attempt;
                }
                if (attempt.why() == Lack.Why.NO_ROUTE) {
                    last = Lack.Why.NO_ROUTE;
                }
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
     * 从 {@code takeoff} 起飞、以 {@code pitch} 抬头爬升去 {@code target} 的一次试排:
     * 巡航高度({@link #cruise})定了,爬升线 + 进近的线({@code way})定了,用实飞的那个局部控制器({@link Pilot})在<b>活世界</b>里从起跳之后的空中起模拟:先只飞爬升那一段,
     * 通了再整条飞一遍,证明躲得开沿途的方块、飞得到降落点(落得轻)。模拟的轨迹就是实飞的轨迹(同一个控制器、同一个扫路判据),检查的线与交给飞行的线是同一条。
     */
    private static Attempt flight(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos takeoff, float pitch, Goal.Anchor target, Landing land) {
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
        double run = (cruise - p0.y) / Math.tan(Math.toRadians(-pitch));
        if (run + APPROACH > range) {
            // 这个俯仰爬不到巡航高度就已经飞过了目标:换更陡的
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        Vec3 p1 = new Vec3(p0.x + ux * run, cruise, p0.z + uz * run);
        Vec3 touchdown = new Vec3(land.x(), land.y(), land.z());
        List<Vec3> tail = approachCorners(p1, touchdown);
        if (tail == null) {
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        List<Vec3> corners = new ArrayList<>();
        corners.add(p0);
        corners.addAll(tail);
        Airway way = Airway.through(corners, touchdown).withZone(TOLERANCE).withClimb(p0.distanceTo(p1));
        Mobility mobility = snapshot.mobility();
        double gravity = rig.entity.getGravity();
        double eye = rig.entity.getEyeHeight();
        Motion launch = new Motion(p0.add(0, 1.3, 0), Vec3.ZERO);
        Neck facing = Neck.at(Look.yawOf(ux, uz), pitch);
        Airway.Sweep over = over(rig, stats, way);
        // 先只模拟爬升那一段(便宜):头顶挡着的起飞点不必把整条线飞一遍。模拟在活世界里飞,局部控制器躲得开的才算飞得过去——它飞出来的轨迹就是实飞的轨迹
        Result climb = fly(way, gravity, mobility, eye, launch, facing, UNLIMITED, p0.distanceTo(p1), CLIMB_LIMIT, over);
        if (climb == null) {
            PathLog.debug("{} 起飞点 {} 俯仰 {} 爬升飞不出去", rig.who, PathLog.pos(takeoff), pitch);
            return Attempt.not(Lack.Why.NO_TAKEOFF);
        }
        // 再把整条线飞一遍:飞得到降落点(落得轻)
        Result whole = fly(way, gravity, mobility, eye, launch, facing, UNLIMITED, Double.NaN, REHEARSAL, over);
        if (whole == null) {
            PathLog.debug("{} 起飞点 {} 俯仰 {} 整条线飞不到降落点", rig.who, PathLog.pos(takeoff), pitch);
            return Attempt.not(Lack.Why.NO_ROUTE);
        }
        BlockPos cell = land.cell() != null ? land.cell() : BlockPos.containing(land.x(), land.y(), land.z());
        return Attempt.of(new Plan(false, takeoff, pitch, cell, way, whole.ticks(), whole.rockets(), range, target, land.known()));
    }

    /** 在活世界里飞这条线碰不碰方块:实飞操舵读的同一个判据({@link Helm#sweep}),降落点附近允许擦着地面。 */
    private static Airway.Sweep over(Rig rig, BodyStats stats, Airway way) {
        return (from, to) -> Helm.sweep(rig, stats, from, to, way.touchdown(), way.zone());
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
    private static Plan dropping(Rig rig, BodySnapshot snapshot, BodyStats stats, Goal.Anchor target, Set<BlockPos> spent) {
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
            Plan plan = fromEdge(rig, snapshot, stats, edge.cell(), target);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Plan fromEdge(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos edge, Goal.Anchor target) {
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
        Mobility mobility = snapshot.mobility();
        // 走出边沿:带着一点水平速度、刚离地,朝降落点,平视;没有烟花的模拟,只靠高度往下滑
        Motion start = new Motion(p0.add(ux * 1.2, 0, uz * 1.2), new Vec3(ux * 0.2, -0.1, uz * 0.2));
        Result result = fly(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight(), start, Neck.at(Look.yawOf(ux, uz), 0), 0, Double.NaN,
                REHEARSAL, over(rig, stats, way));
        if (result == null) {
            PathLog.debug("{} 不排从边沿走出去的滑翔 {}:模拟落不到降落点", rig.who, PathLog.pos(edge));
            return null;
        }
        return new Plan(true, edge, 0, landing, way, result.ticks(), 0, Math.hypot(landing.getX() - edge.getX(), landing.getZ() - edge.getZ()), target, true);
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
                    if (cell != null) {
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
     * 前方被挡:从空中的 {@code pos} 抬高巡航重排一条线(再爬一段,过得去再进近到原来的降落点),飞得完才算;
     * 烟花少于 {@link #ROCKET_FLOOR}、爬不过去(到了建筑高度的顶)、模拟飞不完为 null。
     */
    static Airway raise(Rig rig, Airway current, Vec3 pos, Motion from, Neck facing) {
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
            if (!corridor(rig, stats, corners, ux, uz)) {
                continue;
            }
            Airway way = Airway.through(corners, touchdown).withZone(TOLERANCE);
            Result result = fly(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight(), from, facing, UNLIMITED, Double.NaN, REHEARSAL,
                    over(rig, stats, way));
            return result != null ? way : null;
        }
        return null;
    }

    /** 模拟的结果:飞了几刻、放了几支、落在哪,和身体每刻到过的点。 */
    record Result(int ticks, int rockets, Vec3 landedAt, List<Vec3> path) {}

    /**
     * 拿同一个局部控制器把线飞一遍:从 {@code from}(起跳之后的空中)起,{@code facing} 是此刻颈部的样子,烟花不超过 {@code supply} 支,
     * 碰不碰方块读 {@code sweep}(排线时是活世界,单测是空场)。
     * {@code stopArc} 不是 NaN 时,在线上走到这个弧长就停(爬升的检查:只飞线的头一段,轨迹随结果交出),走不到为 null;是 NaN 时一直飞到落地,
     * 飞不到降落点附近或落得重为 null。最多飞 {@code limit} 刻。放烟花也守同一套间隔。
     */
    static Result fly(Airway way, double gravity, Mobility mobility, double eye, Motion from, Neck facing, int supply, double stopArc, int limit,
                      Airway.Sweep sweep) {
        Dynamics glide = Dynamics.gliding(gravity, mobility.rocketBurn());
        Pilot pilot = new Pilot(glide, sweep, eye);
        Vec3 touchdown = way.touchdown();
        Motion m = from;
        Neck neck = facing;
        int fired = 0;
        int burning = 0;
        int cooldown = 0;
        double s = 0;
        List<Vec3> path = new ArrayList<>();
        path.add(m.pos());
        int budget = Double.isNaN(stopArc) ? (int) Math.min(limit, SIM_BASE + SIM_PER_BLOCK * way.length()) : limit;
        for (int tick = 0; tick < budget; tick++) {
            s = way.project(m.pos(), s);
            if (burning > 0) {
                burning--;
            }
            if (cooldown > 0) {
                cooldown--;
            }
            Action pick = pilot.choose(m, neck, way, s, false, new Supply(cooldown == 0 && burning == 0 ? supply - fired : 0, burning));
            if (pick == null) {
                return null;
            }
            neck = neck.toward(pick.yaw(), pick.pitch());
            if (pick.keys().rocket() && cooldown == 0 && burning == 0 && supply - fired > 0) {
                fired++;
                burning = glide.burn();
                cooldown = Helm.COOLDOWN;
            }
            m = glide.step(m, pick.keys().facing(neck.yaw(), neck.pitch()).burning(burning > 0));
            path.add(m.pos());
            if (!Double.isNaN(stopArc)) {
                if (way.project(m.pos(), s) >= stopArc) {
                    return new Result(tick + 1, fired, m.pos(), path);
                }
            } else if (m.pos().y <= touchdown.y) {
                // 落点要近,落得要轻:模拟里就落重了的不排(实际飞起来照同一个控制器,落得重要扣分)
                double miss = Math.hypot(m.pos().x - touchdown.x, m.pos().z - touchdown.z);
                return miss <= TOLERANCE && glide.impact(m) == 0 ? new Result(tick + 1, fired, m.pos(), path) : null;
            }
        }
        return null;
    }
}
