package com.dwinovo.numen.pathing.drive;

import java.util.Comparator;
import java.util.List;

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
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 一次滑翔的计划:用不用、从哪起飞、在哪降落、沿什么线飞。滑翔不进格子搜索(转弯半径大、停不下来,状态里得有速度),
 * 是行程里的一段({@link Voyage}),这里是上层:定巡航高度与走廊,下层是局部控制器({@link Pilot},物理 {@link Dynamics#gliding})。
 *
 * <h2>用不用</h2>
 * 身体有可用的鞘翅、目标有水平位置({@link Goal#anchor}:一格、一列、一个范围都有),而且"走到起飞点 + 飞 + 降落后走到目标"的估计耗时低于步行的下界
 * (水平距离乘疾跑一格的耗时,{@link Kinematics#pace})时才排滑翔段;全凭价钱,没有距离的门槛,也没有目标格数的门槛:演练之前先拿便宜的下界筛一遍
 * (起飞的固定开销加滑翔的最快水平速度,比不过步行的下界就不演练)。飞这一段的耗时与烟花用量不是另写的公式:拿同一个局部控制器在没有障碍的空场里
 * 把这条线飞一遍({@link #rehearse}),数它用了几刻、放了几支。烟花与鞘翅耐久按估计留余量({@link #MARGIN});不够飞完全程就飞一部分
 * ({@link #SHORTENED}:从全程往短里试),落地后走路的段接着走。不排滑翔的原因照实记进账({@link EditLedger.Grounded}),只在远得值得一提时。
 *
 * <h2>两种起飞</h2>
 * 有烟花:平地起跳、展开、放烟花爬到巡航高度再飞(下面"线")。没有烟花,或者从高处走下去更省:走到一处落差够大的边沿,
 * 从边沿走出去、离地后展开,只靠高度往下滑——没有爬升和巡航,线是边沿到降落点的下滑线;落差够不够,由能不能滑到降落点、价钱比不比走路省定,没有定死的最小落差。
 * 两种都排得成,取估计更快的。
 *
 * <h2>线</h2>
 * 起飞点→爬到巡航高度(水平一格升一格)→平飞→按滑翔比往降落点下滑。巡航高度从起飞点之上最低的 {@link #MIN_CLIMB} 格起往上试,取第一个"线和线两侧各
 * {@link #SIDE} 格的平行线都扫得过去"的(碰撞扫掠,不看高度图:洞顶、屋檐在高度图里,而要的是飞得过去),一直试到建筑高度的顶(物理上限)。
 * 没加载的区块是未知,不是空气也不是墙:扫掠读它是空的,先飞,飞到哪加载到哪,{@link Sortie} 每刻接着查前方新加载的那段线({@link Helm#blocked}),
 * 被挡就抬高巡航重排({@link #raise})或改降。降落点落在目标这一侧 {@link #APPROACH} 格处的地面上,最后一截落地后步行段走完;
 * 目标那片没加载时降落点先按起飞点的高度估(演练用),那片加载了再定真正的落点({@link #settle})。
 */
final class Glide {

    /** 鞘翅水平速度的粗上界(格每刻,俯冲能到的):便宜的下界筛用,真的耗时由演练算。 */
    private static final double MAX_GLIDE_SPEED = 3.5;
    /** 从边沿走出去展开的固定开销下界(刻):筛用。 */
    private static final double DROP_TICKS = 4;
    /** 找边沿往四周看几格(算力预算:边沿、起飞点都是离身体近的先试,走过去的路程已经算在估计里)。 */
    private static final int EDGE_RADIUS = 16;
    private static final int TAKEOFF_RADIUS = 16;
    /** 起飞点、边沿最多试几个(算力预算:每试一个都是一遍整线的演练)。 */
    private static final int TAKEOFFS_TRIED = 6;
    private static final int EDGES_TRIED = 4;
    /** 没有烟花时下滑线能飞多远(滑翔比的这个比例,留余量)。 */
    private static final double GLIDE_SHARE = 0.65;
    /** 线两侧各留几格也要飞得过去。 */
    private static final int SIDE = 2;
    /** 巡航高度:起飞点之上,从最低的开始每次多高几格往上试。 */
    private static final int MIN_CLIMB = 10;
    private static final int CLIMB_STEP = 6;
    /** 滑翔比:每下降一格水平走几格(规划的下滑线;鞘翅平滑翔大约八九比一)。 */
    static final double GLIDE_RATIO = 9.0;
    /** 起飞时抬头的俯仰(度):爬升,但不直拉到垂直。 */
    static final float CLIMB_PITCH = -45.0F;
    /** 降落点落在目标这一侧多少格处:最后一截落地后走过去。 */
    private static final double APPROACH = 6.0;
    /** 找降落点往四周看几格(从近到远,第一圈有地面就停)。 */
    private static final int LANDING_RADIUS = 12;
    /** 烟花、耐久的余量:估计用量乘它再加一,不够就不飞。 */
    private static final double MARGIN = 1.5;
    /** 起飞(助跑跳、展开)花的刻数,和落地后的收尾。 */
    private static final double TAKEOFF_TICKS = 8;
    /** 演练最多飞几刻;飞不到这么久就落不了地,不排。 */
    private static final int REHEARSAL = 1200;
    /** 落地离降落点水平不超过这么远就算降到了(演练与实飞同一个判据,飞行线的降落点那一圈就是它):落在圈里往下走几步就到,不必一格不差。 */
    private static final double TOLERANCE = 6.0;
    /** 不够飞完全程时,飞全程的这些比例试(从长到短)。 */
    private static final double[] SHORTENED = {0.75, 0.5, 0.3};
    /** 回执里提一句"为什么没飞",要飞得起来至少省这么多刻(十秒);近处走几步的事不提。 */
    private static final double SAVING = 200;

    /**
     * 一份滑翔计划。
     *
     * @param drop      从边沿走出去起飞(不起跳、不爬升);否则平地起跳
     * @param takeoff   起飞的格(站着的节点)
     * @param landing   降落的格(落地站着的节点);目标那片没加载时是按起飞点高度估的那一格
     * @param way       沿着飞的线,终点是降落点
     * @param ticks     估计飞行用几刻(演练)
     * @param rockets   估计用几支烟花(演练)
     * @param wear      估计鞘翅耗几点耐久
     * @param estimate  走到起飞点 + 飞 + 降落后走到目标的估计总耗时(刻)
     * @param walkBound 全程步行的下界(刻)
     * @param blocks    起飞点到降落点的水平距离
     * @param aim       这一趟飞往的水平位置(烟花或耐久不够飞全程时是全程的一部分)
     * @param known     降落点是真的地面;否则那片没加载,降落点是估的,{@link Sortie} 之后那片加载了再定({@link #settle})
     */
    record Plan(boolean drop, BlockPos takeoff, BlockPos landing, Airway way, int ticks, int rockets, int wear, double estimate,
                double walkBound, double blocks, Goal.Anchor aim, boolean known) {}

    /** 排的结果:飞,是计划;不飞而值得一提(远途),是原因;近处走几步的事两样都没有。 */
    record Verdict(Plan plan, EditLedger.Grounded grounded) {
        static final Verdict NONE = new Verdict(null, null);
    }

    /** 一次试排:成了是计划;没成是原因,和减短飞行有没有用({@code shorter}:烟花、耐久不够或演练落不到点,飞短一点也许行;线被挡、没有起飞的地方,短了也一样)。 */
    private record Attempt(Plan plan, EditLedger.Grounded why, boolean shorter) {

        static Attempt of(Plan plan) {
            return new Attempt(plan, null, false);
        }

        static Attempt not(EditLedger.Why why, int need, int have, boolean shorter) {
            return new Attempt(null, new EditLedger.Grounded(why, need, have), shorter);
        }
    }

    /** 降落点:{@code cell} 是真的地面,{@code known} 为 false 时那片没加载,只有水平位置和估的高度。 */
    private record Landing(BlockPos cell, double x, double z, double y, boolean known) {}

    private Glide() {}

    /** 要不要飞、怎么飞;不排滑翔,计划为 null,远得值得一提时带着原因。 */
    static Verdict plan(Rig rig, Goal goal) {
        ServerPlayer body = rig.entity;
        BodySnapshot snapshot = rig.snapshot();
        Mobility mobility = snapshot.mobility();
        if (mobility.flying() || body.isFallFlying()) {
            return Verdict.NONE;
        }
        Goal.Anchor target = goal.anchor(body.position());
        if (target == null) {
            return Verdict.NONE;
        }
        BodyStats stats = snapshot.stats();
        double range = target.distance(body.position());
        double pace = Kinematics.pace(stats).sprint();
        double walkBound = range * pace;
        // 便宜的下界:起飞的固定开销,加最快的水平速度飞完(除去最后一截要走的),加最后一截走过去;比不过步行的下界,演练都不用做
        double lower = DROP_TICKS + Math.max(0, range - APPROACH) / MAX_GLIDE_SPEED + Math.min(range, APPROACH) * pace;
        if (lower >= walkBound) {
            PathLog.debug("{} 不排滑翔:再快也比不过走路的下界 {} 刻", rig.who, PathLog.num(walkBound));
            return Verdict.NONE;
        }
        boolean worth = walkBound - lower >= SAVING;
        if (!mobility.canGlide()) {
            return refused(rig, worth, new EditLedger.Grounded(EditLedger.Why.NO_ELYTRA, 0, 0));
        }
        Plan chosen = null;
        EditLedger.Grounded why = null;
        if (mobility.fireworks() > 0) {
            int tried = 0;
            for (BlockPos takeoff : takeoffs(rig, stats, (walkBound - lower) / pace)) {
                if (tried++ >= TAKEOFFS_TRIED) {
                    break;
                }
                Attempt attempt = through(rig, snapshot, stats, takeoff, target, walkBound);
                if (attempt.plan() != null) {
                    chosen = attempt.plan();
                    break;
                }
                why = attempt.why();
                if (why.why() != EditLedger.Why.NO_TAKEOFF) {
                    break;
                }
            }
            if (chosen == null && why == null) {
                why = new EditLedger.Grounded(EditLedger.Why.NO_TAKEOFF, 0, 0);
            }
        } else {
            why = new EditLedger.Grounded(EditLedger.Why.NO_LIFT, 0, 0);
        }
        Plan dropped = dropping(rig, snapshot, stats, target, walkBound);
        if (dropped != null && (chosen == null || dropped.estimate() < chosen.estimate())) {
            chosen = dropped;
        }
        if (chosen == null) {
            return refused(rig, worth, why);
        }
        if (chosen.estimate() >= walkBound) {
            PathLog.debug("{} 不排滑翔:估 {} 刻不比走路的下界 {} 刻快", rig.who, PathLog.num(chosen.estimate()), PathLog.num(walkBound));
            return Verdict.NONE;
        }
        return new Verdict(chosen, null);
    }

    private static Verdict refused(Rig rig, boolean worth, EditLedger.Grounded why) {
        PathLog.debug("{} 不排滑翔:{}", rig.who, describe(why));
        return worth ? new Verdict(null, why) : Verdict.NONE;
    }

    /** 不飞的原因,写进日志。 */
    static String describe(EditLedger.Grounded why) {
        return switch (why.why()) {
            case NO_ELYTRA -> "没穿可用的鞘翅";
            case NO_LIFT -> "没有烟花,附近也没有够高的边沿可以滑下去";
            case TOO_FEW_ROCKETS -> "烟花不够:飞一段要约 " + why.need() + " 支,有 " + why.have() + " 支";
            case WORN_ELYTRA -> "鞘翅耐久不够:要约 " + why.need() + " 点,剩 " + why.have() + " 点";
            case NO_TAKEOFF -> "找不到起飞的地方";
            case NO_ROUTE -> "任何高度的线都过不去前面的地形";
            case NO_LANDING -> "目标附近找不到站得住的地面降落";
        };
    }

    /**
     * 从边沿走出去的计划:身体附近落差大的边沿(那一格站得住,朝目标走过去的前面没有地面)落差大的先试,最多试 {@link #EDGES_TRIED} 个;
     * 线是边沿到降落点的直线下滑线,降落点离边沿不超过滑翔比的 {@link #GLIDE_SHARE}。没有烟花时落点够不够得着全靠已知的地面,所以降落点必须是真的
     * (那片没加载的不排)。没有为 null。
     */
    private static Plan dropping(Rig rig, BodySnapshot snapshot, BodyStats stats, Goal.Anchor target, double walkBound) {
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
        List<Edge> edges = new java.util.ArrayList<>();
        for (int x = -EDGE_RADIUS; x <= EDGE_RADIUS; x++) {
            for (int z = -EDGE_RADIUS; z <= EDGE_RADIUS; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos cell = at.offset(x, y, z);
                    Stance stance = Stance.at(rig.world(), stats, cell);
                    if (stance == null || !stance.grounded()) {
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
            Plan plan = fromEdge(rig, snapshot, stats, edge.cell(), target, walkBound);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Plan fromEdge(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos edge, Goal.Anchor target, double walkBound) {
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
        // 走出边沿:带着一点水平速度、刚离地,朝降落点,平视
        Motion start = new Motion(p0.add(ux * 1.2, 0, uz * 1.2), new Vec3(ux * 0.2, -0.1, uz * 0.2));
        Result result = rehearse(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight(), start,
                Neck.at(Look.yawOf(ux, uz), 0));
        if (result == null || !enough(result.rockets, mobility.fireworks()) || !enough(result.ticks / 20 + 1, mobility.gliderLife())) {
            PathLog.debug("{} 不排从边沿走出去的滑翔 {}:演练不成或烟花耐久不够", rig.who, PathLog.pos(edge));
            return null;
        }
        double pace = Kinematics.pace(stats).sprint();
        double estimate = Math.hypot(edge.getX() + 0.5 - rig.entity.getX(), edge.getZ() + 0.5 - rig.entity.getZ()) * pace + DROP_TICKS + result.ticks
                + target.distance(touchdown) * pace;
        return new Plan(true, edge, landing, way, result.ticks, result.rockets, result.ticks / 20 + 1, estimate, walkBound,
                Math.hypot(landing.getX() - edge.getX(), landing.getZ() - edge.getZ()), target, true);
    }

    /** 站在地面上、离身体近的几个格,近的在前:起飞点从这里挑。走过去的路程要付价钱,{@code affordable} 格以外的飞起来也不比走路省,不找。 */
    private static List<BlockPos> takeoffs(Rig rig, BodyStats stats, double affordable) {
        BlockPos at = rig.entity.blockPosition();
        int radius = (int) Math.min(TAKEOFF_RADIUS, Math.ceil(affordable));
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos cell = at.offset(x, y, z);
                    Stance stance = Stance.at(rig.world(), stats, cell);
                    if (stance != null && stance.grounded()) {
                        out.add(cell);
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(cell -> cell.distSqr(at)));
        return out;
    }

    /**
     * 从 {@code takeoff} 起飞去 {@code target} 的计划。烟花或耐久不够飞全程(或演练落不到点),就飞全程的一部分({@link #SHORTENED}),
     * 落地后走路的段接着走。
     */
    private static Attempt through(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos takeoff, Goal.Anchor target, double walkBound) {
        Attempt full = null;
        for (int i = 0; i <= SHORTENED.length; i++) {
            double share = i == 0 ? 1.0 : SHORTENED[i - 1];
            double tx = takeoff.getX() + 0.5;
            double tz = takeoff.getZ() + 0.5;
            Goal.Anchor aim = i == 0 ? target : new Goal.Anchor(tx + (target.x() - tx) * share, tz + (target.z() - tz) * share);
            Attempt attempt = flight(rig, snapshot, stats, takeoff, target, aim, walkBound);
            if (attempt.plan() != null) {
                return attempt;
            }
            if (i == 0) {
                full = attempt;
            }
            if (!attempt.shorter()) {
                break;
            }
        }
        return full;
    }

    /** 从 {@code takeoff} 起飞去 {@code aim} 的一次试排;线上有障碍在任何巡航高度都过不去、演练落不到降落点、烟花耐久不够,是原因。 */
    private static Attempt flight(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos takeoff, Goal.Anchor target, Goal.Anchor aim,
                                  double walkBound) {
        double dx = aim.x() - (takeoff.getX() + 0.5);
        double dz = aim.z() - (takeoff.getZ() + 0.5);
        double range = Math.hypot(dx, dz);
        if (range < 1) {
            return Attempt.not(EditLedger.Why.NO_ROUTE, 0, 0, false);
        }
        double ux = dx / range;
        double uz = dz / range;
        double y0 = takeoff.getY();
        Vec3 p0 = new Vec3(takeoff.getX() + 0.5, y0, takeoff.getZ() + 0.5);
        int top = rig.world().getMinBuildHeight() + rig.world().getHeight() - 2;
        EditLedger.Why last = EditLedger.Why.NO_ROUTE;
        boolean first = true;
        // 巡航高度从最低的开始往上试,一直试到建筑高度的顶:爬(一格水平一格高)加下滑(滑翔比)放得进水平距离里,而且线两侧各留两格也飞得过去
        for (int climb = MIN_CLIMB; y0 + climb <= top; climb += CLIMB_STEP) {
            double cruise = y0 + climb;
            Vec3 p1 = new Vec3(p0.x + ux * climb, cruise, p0.z + uz * climb);
            if (first) {
                first = false;
                // 起飞点头顶的爬升轨迹都扫不过去:这里起飞不了,换一个起飞点
                if (!corridor(rig, stats, List.of(p0.add(0, 1.3, 0), p1), ux, uz)) {
                    return Attempt.not(EditLedger.Why.NO_TAKEOFF, 0, 0, false);
                }
            }
            Landing land = landing(rig, stats, aim, ux, uz, range, cruise, y0, true);
            if (land == null) {
                // 目标那片加载了却一处站得住的地面也没有(水、虚空):抬高巡航也一样
                return Attempt.not(EditLedger.Why.NO_LANDING, 0, 0, false);
            }
            double run = Math.hypot(land.x() - p0.x, land.z() - p0.z);
            double descent = (cruise - land.y()) * GLIDE_RATIO;
            if (climb + descent > 0.95 * run) {
                break;
            }
            Vec3 touchdown = new Vec3(land.x(), land.y(), land.z());
            Vec3 p2 = new Vec3(touchdown.x - ux * descent, cruise, touchdown.z - uz * descent);
            if (!corridor(rig, stats, List.of(p0.add(0, 1.3, 0), p1, p2, touchdown), ux, uz)) {
                last = EditLedger.Why.NO_ROUTE;
                continue;
            }
            Airway way = Airway.through(List.of(p0, p1, p2, touchdown), touchdown).withZone(TOLERANCE);
            Mobility mobility = snapshot.mobility();
            Result result = rehearse(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight());
            if (result == null) {
                PathLog.debug("{} 不排滑翔:演练飞不到降落点", rig.who);
                return Attempt.not(EditLedger.Why.NO_ROUTE, 0, 0, true);
            }
            int wear = result.ticks / 20 + 1;
            if (!enough(result.rockets, mobility.fireworks())) {
                return Attempt.not(EditLedger.Why.TOO_FEW_ROCKETS, needed(result.rockets), mobility.fireworks(), true);
            }
            if (!enough(wear, mobility.gliderLife())) {
                return Attempt.not(EditLedger.Why.WORN_ELYTRA, needed(wear), mobility.gliderLife(), true);
            }
            double pace = Kinematics.pace(stats).sprint();
            double estimate = Math.hypot(takeoff.getX() + 0.5 - rig.entity.getX(), takeoff.getZ() + 0.5 - rig.entity.getZ()) * pace
                    + TAKEOFF_TICKS + result.ticks + target.distance(touchdown) * pace;
            BlockPos cell = land.cell() != null ? land.cell() : BlockPos.containing(land.x(), land.y(), land.z());
            return Attempt.of(new Plan(false, takeoff, cell, way, result.ticks, result.rockets, wear, estimate, walkBound, run, aim, land.known()));
        }
        return Attempt.not(last, 0, 0, false);
    }

    /** 要用 {@code used} 件、身上有 {@code have} 件,够不够(含余量:估计用量乘 {@link #MARGIN} 再加一;一件不用就不查)。 */
    private static boolean enough(int used, int have) {
        return used == 0 || needed(used) <= have;
    }

    /** 估计用 {@code used} 件,带余量要备几件。 */
    private static int needed(int used) {
        return (int) Math.ceil(used * MARGIN + 1);
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
     * 降落点:目标这一侧 {@link #APPROACH} 格处,从 {@code cruise} 高度往下找站得住的地面(飞机下面最高的那一层,不是高度图:洞顶、屋檐也在高度图里),
     * 从近到远一圈一圈找,第一圈有地面就停;排线时往下找到 {@code range / 滑翔比} 格为止(再低的下滑线放不进水平距离里);飞到半路定落点({@code planning} 为 false)时有多低都找,太高的用盘旋下降({@link #approachCorners})。
     * 那片还没加载:降落点只有水平位置,高度按起飞点的估({@code assumed}),{@code known} 为 false。加载了却找不到站得住的地面为 null。
     */
    private static Landing landing(Rig rig, BodyStats stats, Goal.Anchor aim, double ux, double uz, double range, double cruise, double assumed,
                                   boolean planning) {
        double back = Math.min(APPROACH, range / 4);
        int cx = (int) Math.floor(aim.x() - ux * back);
        int cz = (int) Math.floor(aim.z() - uz * back);
        LiveWorld world = rig.world();
        if (!world.isLoaded(cx, cz) || !world.isLoaded(cx - LANDING_RADIUS, cz - LANDING_RADIUS) || !world.isLoaded(cx + LANDING_RADIUS, cz + LANDING_RADIUS)) {
            return new Landing(null, cx + 0.5, cz + 0.5, assumed, false);
        }
        int from = (int) Math.floor(cruise);
        int to = planning ? Math.max(world.getMinBuildHeight(), (int) Math.floor(cruise - range / GLIDE_RATIO)) : world.getMinBuildHeight();
        for (int r = 0; r <= LANDING_RADIUS; r++) {
            BlockPos best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) {
                        continue;
                    }
                    double d = x * x + z * z;
                    BlockPos cell = d < bestDistance ? ground(rig, stats, cx + x, cz + z, from, to) : null;
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

    /** 这一列从 {@code from} 往下到 {@code to} 第一个站得住的格(地面);没有为 null。不看高度图:洞顶、屋檐也在高度图里。 */
    static BlockPos ground(Rig rig, BodyStats stats, int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockPos cell = new BlockPos(x, y, z);
            Stance stance = Stance.at(rig.world(), stats, cell);
            if (stance != null && stance.grounded()) {
                return cell;
            }
        }
        return null;
    }

    // ==================== 边飞边看 ====================

    /**
     * 降落点还没定(目标那片没加载)的飞行:那片加载了,就在那片从此刻的高度往下定真正的降落点,按此刻的位置重排一条往它去的线;
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
        Landing land = landing(rig, stats, plan.aim(), dx / range, dz / range, range, pos.y, plan.takeoff().getY(), false);
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
     * {@link #approach} 线上的拐点,贴在降落点上为 null。高度够用(按滑翔比下滑刚好落在降落点放得进水平距离里):{@code pos}、平飞到按滑翔比下滑的起点、降落点。
     * 高得下不去(离得太近,或者降落点是后来才知道的、比估的低得多):不朝降落点硬俯冲,而是平飞到降落点上空,绕着它盘旋着下降
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
        List<Vec3> corners = new java.util.ArrayList<>();
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
     * 前方被挡:从空中的 {@code pos} 抬高巡航重排一条线(再爬一段,过得去再下滑到原来的降落点),烟花耐久够飞完才算;
     * 没有烟花、爬不过去(到了建筑高度的顶)、演练飞不完为 null。
     */
    static Airway raise(Rig rig, Airway current, Vec3 pos, Motion from, Neck facing) {
        Mobility mobility = Snapshots.mobility(rig.entity);
        Vec3 touchdown = current.touchdown();
        if (touchdown == null || mobility.fireworks() == 0) {
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
        for (int climb = CLIMB_STEP; pos.y + climb <= top; climb += CLIMB_STEP) {
            double cruise = pos.y + climb;
            double descent = (cruise - touchdown.y) * GLIDE_RATIO;
            if (climb + descent > 0.95 * run) {
                return null;
            }
            Vec3 p1 = new Vec3(pos.x + ux * climb, cruise, pos.z + uz * climb);
            Vec3 p2 = new Vec3(touchdown.x - ux * descent, cruise, touchdown.z - uz * descent);
            List<Vec3> corners = List.of(pos, p1, p2, touchdown);
            if (!corridor(rig, stats, corners, ux, uz)) {
                continue;
            }
            Airway way = Airway.through(corners, touchdown).withZone(TOLERANCE);
            Result result = rehearse(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight(), from, facing);
            return result != null && enough(result.rockets, mobility.fireworks()) && enough(result.ticks / 20 + 1, mobility.gliderLife()) ? way : null;
        }
        return null;
    }

    /** 演练的结果:飞了几刻、放了几支、落在哪。 */
    record Result(int ticks, int rockets, Vec3 landedAt) {}

    /**
     * 拿同一个局部控制器把线飞一遍,没有障碍:从起飞点起跳之后的空中起(高 1.3 格、静止、抬头爬升),到落地。飞不到降落点附近、
     * 或久到不合理为 null。放烟花也守同一套间隔与存量。
     */
    static Result rehearse(Airway way, double gravity, Mobility mobility, double eye) {
        Vec3 start = way.start();
        Vec3 aim = way.at(Math.min(way.length(), 8));
        return rehearse(way, gravity, mobility, eye, new Motion(start.add(0, 1.3, 0), Vec3.ZERO),
                Neck.at(Look.yawOf(aim.x - start.x, aim.z - start.z), CLIMB_PITCH));
    }

    /** 从给定的状态起飞一遍:平地起跳是 {@link #rehearse(Airway, double, Mobility, double)} 那一种,从边沿走出去是带着水平速度刚离地。 */
    static Result rehearse(Airway way, double gravity, Mobility mobility, double eye, Motion from, Neck facing) {
        Dynamics glide = Dynamics.gliding(gravity, mobility.rocketBurn());
        Pilot pilot = new Pilot(glide, (a, b) -> null, eye);
        Vec3 touchdown = way.touchdown();
        Motion m = from;
        Neck neck = facing;
        int rockets = mobility.fireworks();
        int fired = 0;
        int burning = 0;
        int cooldown = 0;
        double s = 0;
        for (int tick = 0; tick < REHEARSAL; tick++) {
            s = way.project(m.pos(), s);
            if (burning > 0) {
                burning--;
            }
            if (cooldown > 0) {
                cooldown--;
            }
            Action pick = pilot.choose(m, neck, way, s, false, new Supply(cooldown == 0 && burning == 0 ? rockets - fired : 0, burning));
            if (pick == null) {
                return null;
            }
            neck = neck.toward(pick.yaw(), pick.pitch());
            if (pick.keys().rocket() && cooldown == 0 && burning == 0 && rockets - fired > 0) {
                fired++;
                burning = glide.burn();
                cooldown = Helm.COOLDOWN;
            }
            m = glide.step(m, pick.keys().facing(neck.yaw(), neck.pitch()).burning(burning > 0));
            if (m.pos().y <= touchdown.y) {
                // 落点要近,落得要轻:演练里就落重了的不排(实际飞起来照同一个控制器,落得重要扣分)
                double miss = Math.hypot(m.pos().x - touchdown.x, m.pos().z - touchdown.z);
                return miss <= TOLERANCE && glide.impact(m) == 0 ? new Result(tick + 1, fired, m.pos()) : null;
            }
        }
        return null;
    }
}
