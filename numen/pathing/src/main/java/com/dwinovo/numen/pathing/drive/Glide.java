package com.dwinovo.numen.pathing.drive;

import java.util.Comparator;
import java.util.List;

import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 一次滑翔的计划:用不用、从哪起飞、在哪降落、沿什么线飞。滑翔不进格子搜索(转弯半径大、停不下来,状态里得有速度),
 * 是行程里的一段({@link Voyage}),这里是上层:定巡航高度与走廊,下层是局部控制器({@link Pilot},物理 {@link Dynamics#gliding})。
 *
 * <h2>用不用</h2>
 * 身体有可用的鞘翅、背着烟花、目标是几个有限的格,而且"走到起飞点 + 飞 + 降落后走到目标"的估计耗时低于步行的下界
 * (水平距离乘疾跑一格的耗时,{@link Kinematics#pace})时才排滑翔段;烟花与鞘翅耐久按估计留余量,不够就不飞,全程走。
 * 飞这一段的耗时与烟花用量不是另写的公式:拿同一个局部控制器在没有障碍的空场里把这条线飞一遍({@link #rehearse}),
 * 数它用了几刻、放了几支。
 *
 * <h2>线</h2>
 * 起飞点→爬到巡航高度(水平一格升一格)→平飞→按滑翔比往降落点下滑。巡航高度从起飞点之上最低的 {@link #MIN_CLIMB} 格起往上试,取第一个"线和线两侧各
 * {@link #SIDE} 格的平行线都扫得过去"的(碰撞扫掠,不看高度图:洞顶、屋檐在高度图里,而要的是飞得过去);走廊上有一个区块没加载就不排滑翔
 * ——没加载的区块是"未知",不是空气。降落点落在目标这一侧 {@link #APPROACH} 格处的地面上,最后一截落地后步行段走完。
 */
final class Glide {

    /** 水平不到这么远,不飞。 */
    private static final double MIN_RANGE = 48.0;
    /** 线两侧各留几格也要飞得过去。 */
    private static final int SIDE = 2;
    /** 巡航高度:起飞点之上,从最低的开始每次多高几格往上试,最高到。 */
    private static final int MIN_CLIMB = 10;
    private static final int CLIMB_STEP = 6;
    private static final int MAX_CLIMB = 40;
    /** 找降落点:在起飞点的高度之上、之下各看几格。 */
    private static final int ABOVE = 16;
    private static final int BELOW = 40;
    /** 滑翔比:每下降一格水平走几格(规划的下滑线;鞘翅平滑翔大约八九比一)。 */
    static final double GLIDE_RATIO = 9.0;
    /** 起飞时抬头的俯仰(度):爬升,但不直拉到垂直。 */
    static final float CLIMB_PITCH = -45.0F;
    /** 降落点落在目标这一侧多少格处:最后一截落地后走过去。 */
    private static final double APPROACH = 6.0;
    /** 找起飞点、降落点往四周看几格。 */
    private static final int RADIUS = 2;
    /** 目标最多几个格才排滑翔(范围无边的目标不排)。 */
    private static final int MAX_ENDS = 64;
    /** 烟花、耐久的余量:估计用量乘它再加一,不够就不飞。 */
    private static final double MARGIN = 1.5;
    /** 起飞(助跑跳、展开)花的刻数,和落地后的收尾。 */
    private static final double TAKEOFF_TICKS = 8;
    /** 演练最多飞几刻;飞不到这么久就落不了地,不排。 */
    private static final int REHEARSAL = 1200;
    /** 演练落地离降落点水平不超过这么远。 */
    private static final double TOLERANCE = 6.0;

    /**
     * 一份滑翔计划。
     *
     * @param takeoff   起飞的格(站着的节点)
     * @param landing   降落的格(落地站着的节点)
     * @param way       沿着飞的线,终点是降落点
     * @param ticks     估计飞行用几刻(演练)
     * @param rockets   估计用几支烟花(演练)
     * @param wear      估计鞘翅耗几点耐久
     * @param estimate  走到起飞点 + 飞 + 降落后走到目标的估计总耗时(刻)
     * @param walkBound 全程步行的下界(刻)
     * @param blocks    起飞点到降落点的水平距离
     */
    record Plan(BlockPos takeoff, BlockPos landing, Airway way, int ticks, int rockets, int wear, double estimate, double walkBound,
                double blocks) {}

    private Glide() {}

    /** 要不要飞、怎么飞;不排滑翔为 null。 */
    static Plan plan(Rig rig, Goal goal) {
        ServerPlayer body = rig.entity;
        BodySnapshot snapshot = rig.snapshot();
        Mobility mobility = snapshot.mobility();
        LongSet ends = goal.endCells();
        if (!mobility.canGlide() || mobility.fireworks() <= 0 || mobility.flying() || body.isFallFlying() || ends == null || ends.isEmpty()
                || ends.size() > MAX_ENDS) {
            return null;
        }
        BodyStats stats = snapshot.stats();
        BlockPos target = nearest(ends, body.position());
        double dx = target.getX() + 0.5 - body.getX();
        double dz = target.getZ() + 0.5 - body.getZ();
        double range = Math.hypot(dx, dz);
        double walkBound = range * Kinematics.pace(stats).sprint();
        if (range < MIN_RANGE) {
            PathLog.debug("{} 不排滑翔:只有 {} 格", rig.who, PathLog.num(range));
            return null;
        }
        for (BlockPos takeoff : takeoffs(rig, stats)) {
            Plan plan = through(rig, snapshot, stats, takeoff, target, walkBound);
            if (plan != null) {
                if (plan.estimate() >= walkBound) {
                    PathLog.debug("{} 不排滑翔:估 {} 刻不比走路的下界 {} 刻快", rig.who, PathLog.num(plan.estimate()), PathLog.num(walkBound));
                    return null;
                }
                return plan;
            }
        }
        return null;
    }

    /** 站在地面上、离身体近的几个格,近的在前:起飞点从这里挑。 */
    private static List<BlockPos> takeoffs(Rig rig, BodyStats stats) {
        BlockPos at = rig.entity.blockPosition();
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
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

    private static BlockPos nearest(LongSet cells, Vec3 from) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (long packed : cells) {
            BlockPos cell = BlockPos.of(packed);
            double d = Math.hypot(cell.getX() + 0.5 - from.x, cell.getZ() + 0.5 - from.z);
            if (d < bestDistance) {
                bestDistance = d;
                best = cell;
            }
        }
        return best;
    }

    /** 从 {@code takeoff} 起飞去 {@code target} 的计划;线上有障碍、沿途有没加载的区块、演练落不到降落点为 null。 */
    private static Plan through(Rig rig, BodySnapshot snapshot, BodyStats stats, BlockPos takeoff, BlockPos target, double walkBound) {
        ServerLevel level = rig.entity.serverLevel();
        double dx = target.getX() - takeoff.getX();
        double dz = target.getZ() - takeoff.getZ();
        double range = Math.hypot(dx, dz);
        double ux = dx / range;
        double uz = dz / range;
        BlockPos landing = landing(rig, stats, takeoff, target, ux, uz, range);
        if (landing == null) {
            PathLog.debug("{} 不排滑翔:找不到降落点", rig.who);
            return null;
        }
        if (!loaded(level, takeoff, landing)) {
            // 没加载的区块是未知,不是空气:不排
            PathLog.debug("{} 不排滑翔:沿途有没加载的区块", rig.who);
            return null;
        }
        double run = Math.hypot(landing.getX() - takeoff.getX(), landing.getZ() - takeoff.getZ());
        double y0 = takeoff.getY();
        double yL = landing.getY();
        Vec3 p0 = new Vec3(takeoff.getX() + 0.5, y0, takeoff.getZ() + 0.5);
        Vec3 touchdown = new Vec3(landing.getX() + 0.5, yL, landing.getZ() + 0.5);
        // 巡航高度从最低的开始往上试:爬(一格水平一格高)加下滑(滑翔比)放得进水平距离里,而且线两侧各留两格也飞得过去
        Airway way = null;
        for (int climb = MIN_CLIMB; climb <= MAX_CLIMB; climb += CLIMB_STEP) {
            double cruise = y0 + climb;
            double descent = (cruise - yL) * GLIDE_RATIO;
            if (climb + descent > 0.95 * run) {
                break;
            }
            Vec3 p1 = new Vec3(p0.x + ux * climb, cruise, p0.z + uz * climb);
            Vec3 p2 = new Vec3(touchdown.x - ux * descent, cruise, touchdown.z - uz * descent);
            if (corridor(rig, stats, List.of(p0.add(0, 1.3, 0), p1, p2, touchdown), ux, uz)) {
                way = Airway.through(List.of(p0, p1, p2, touchdown), touchdown);
                break;
            }
        }
        if (way == null) {
            PathLog.debug("{} 不排滑翔:{} 格的线在任何巡航高度上都过不去", rig.who, PathLog.num(run));
            return null;
        }
        Mobility mobility = snapshot.mobility();
        Result result = rehearse(way, rig.entity.getGravity(), mobility, rig.entity.getEyeHeight());
        if (result == null) {
            PathLog.debug("{} 不排滑翔:演练飞不到降落点", rig.who);
            return null;
        }
        int wear = result.ticks / 20 + 1;
        if (result.rockets * MARGIN + 1 > mobility.fireworks() || wear * MARGIN + 1 > mobility.gliderLife()) {
            PathLog.debug("{} 不排滑翔:要烟花 {} 支(有 {}),鞘翅耗 {} 点(有 {})", rig.who, result.rockets, mobility.fireworks(), wear,
                    mobility.gliderLife());
            return null;
        }
        double pace = Kinematics.pace(stats).sprint();
        double estimate = Math.hypot(takeoff.getX() + 0.5 - rig.entity.getX(), takeoff.getZ() + 0.5 - rig.entity.getZ()) * pace
                + TAKEOFF_TICKS + result.ticks + Math.hypot(target.getX() - landing.getX(), target.getZ() - landing.getZ()) * pace;
        return new Plan(takeoff, landing, way, result.ticks, result.rockets, wear, estimate, walkBound, run);
    }

    /** 沿 {@code corners} 这条折线飞,线上和线两侧各 {@link #SIDE} 格的平行线都扫得过去(身体盒不碰方块)。 */
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
     * 降落点:目标这一侧 {@link #APPROACH} 格处,在起飞点的高度上下找站得住的地面(从高往低找第一个);附近没有为 null。
     * 起降的高度不靠高度图:洞顶、屋檐也在高度图里,而这里要的是飞得到、站得住的那一格。
     */
    private static BlockPos landing(Rig rig, BodyStats stats, BlockPos takeoff, BlockPos target, double ux, double uz, double range) {
        double back = Math.min(APPROACH, range / 4);
        int cx = (int) Math.floor(target.getX() + 0.5 - ux * back);
        int cz = (int) Math.floor(target.getZ() + 0.5 - uz * back);
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                double d = x * x + z * z;
                if (d >= bestDistance) {
                    continue;
                }
                BlockPos cell = ground(rig, stats, cx + x, cz + z, takeoff.getY() + ABOVE, takeoff.getY() - BELOW);
                if (cell != null) {
                    best = cell;
                    bestDistance = d;
                }
            }
        }
        return best;
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

    /** 起点到终点的走廊(两侧各 {@link #SIDE} 格)上的区块都加载了。未知不是空气。 */
    private static boolean loaded(ServerLevel level, BlockPos from, BlockPos to) {
        double length = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int steps = Math.max(1, (int) Math.ceil(length / 8));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * t);
            for (int sx = -SIDE; sx <= SIDE; sx += SIDE) {
                for (int sz = -SIDE; sz <= SIDE; sz += SIDE) {
                    if (level.getChunkSource().getChunkNow((x + sx) >> 4, (z + sz) >> 4) == null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 演练的结果:飞了几刻、放了几支、落在哪。 */
    record Result(int ticks, int rockets, Vec3 landedAt) {}

    /**
     * 拿同一个局部控制器把线飞一遍,没有障碍:从起飞点起跳之后的空中起(高 1.3 格、静止、抬头爬升),到落地。飞不到降落点附近、
     * 或久到不合理为 null。放烟花也守同一套间隔与存量。
     */
    static Result rehearse(Airway way, double gravity, Mobility mobility, double eye) {
        Dynamics glide = Dynamics.gliding(gravity, mobility.rocketBurn());
        Pilot pilot = new Pilot(glide, (from, to) -> null, eye);
        Vec3 touchdown = way.touchdown();
        Vec3 start = way.start();
        Vec3 aim = way.at(Math.min(way.length(), 8));
        Motion m = new Motion(start.add(0, 1.3, 0), Vec3.ZERO);
        Neck neck = Neck.at(Look.yawOf(aim.x - start.x, aim.z - start.z), CLIMB_PITCH);
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
