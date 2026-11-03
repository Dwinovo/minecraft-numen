package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 滑翔的一段:从起飞点起跳、空中展开鞘翅、放烟花爬升,沿计划的线飞({@link Glide.Plan}),落地。合法执行:只按视角(颈部模型)、
 * 跳(按下沿展开)、右键烟花,不改速度、坐标、计时器,不发原版客户端不会发的包;不替她穿鞘翅、不去箱子里拿烟花(身上有什么就用什么)。
 *
 * <ul>
 *   <li><b>起飞</b>:先把烟花拿到手上(换手记进身体动作),抬头朝巡航方向,起跳,松开,离地之后再按一下跳(新的按下沿)展开;展开了就是滑翔;</li>
 *   <li><b>飞</b>:局部控制器({@link Pilot},物理 {@link Dynamics#gliding})沿线飞({@link Helm}),烟花够用才放;撞墙的推演被否决;</li>
 *   <li><b>迫降</b>:烟花用完又够不着降落点,或前方推演全被否决,就近挑一处站得住的地面,改沿它下滑,照实记一笔(迫降);</li>
 *   <li><b>落地</b>:脚着地、滑翔停了。收场记一笔账({@link EditLedger.Glided}):起降的位置、飞了多远、用了几支烟花、鞘翅耐久前后、落地伤了几点血。</li>
 * </ul>
 */
final class Sortie implements Segment {

    private enum Phase { TAKEOFF, GLIDE }

    /** 视线离爬升方向不到这么多度(两个方向)才起跳;转头最多几刻。 */
    private static final float ALIGNED = 4.0F;
    private static final int ALIGN_LIMIT = 60;
    /** 起跳之后最多几刻,展开不了就放弃。 */
    private static final int TAKEOFF_LIMIT = 30;
    /** 展开那一下跳之后几刻还没展开,就再按一次。 */
    private static final int UNFURL_RETRY = 3;
    /** 迫降:往四周找站得住的地面,最远找多少格,和滑翔比的留量。 */
    private static final int EMERGENCY_RADIUS = 48;
    private static final double REACH_MARGIN = 0.8;
    private static final int EMERGENCY_STEP = 3;
    /** 迫降:落点的水平距离至少是下降高度的几倍(再陡就得俯冲,落得重)。 */
    private static final double MIN_RATIO = 4.0;
    /** 迫降的降落点那一圈的半径:地面当作落点那一格的高度。 */
    private static final double EMERGENCY_ZONE = 12.0;
    /** 盘旋:半径、每隔几刻再找一次落点、最多盘旋几刻。 */
    private static final double LOITER_RADIUS = 14.0;
    private static final int LOITER_RETRY = 10;
    private static final int LOITER_LIMIT = 400;
    /** 身体在线上的进度一刻往前最多找这么多格({@link Airway#project}):身体一刻飞不到这么远,留余量。 */
    private static final double PROGRESS_REACH = 8.0;
    /** 目标那片没加载的飞行:每隔几刻看一眼那片加载了没有。 */
    private static final int SETTLE_EVERY = 10;

    private final Rig rig;
    private final Glide.Plan plan;
    private final Helm helm;
    private Airway way;
    private Phase phase = Phase.TAKEOFF;
    private Driver.State state = Driver.State.RUNNING;
    private Halt halt;
    private int ticks;
    private int takeoffTicks;
    /** 转头对准用了几刻。 */
    private int aligning;
    private int pressedAt = -1;
    private double s;
    private boolean emergency;
    /** 在盘旋等落点:从第几刻起。 */
    private boolean loitering;
    /** 降落点已经定过(目标那片加载了)。 */
    private boolean settled;
    private int loiterSince;
    private final int wearBefore;
    private final float healthBefore;
    private final Vec3 launchedAt;

    Sortie(Rig rig, Glide.Plan plan) {
        this.rig = rig;
        this.plan = plan;
        this.way = plan.way();
        this.helm = new Helm(rig, Dynamics.gliding(rig.entity.getGravity(), rig.snapshot().mobility().rocketBurn(), rig.snapshot().safeFallDistance()));
        this.wearBefore = rig.entity.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
        this.healthBefore = rig.entity.getHealth();
        this.launchedAt = rig.entity.position();
        PathLog.info("{} 滑翔 从 {} 到 {} 水平 {} 格 {}", rig.who, PathLog.pos(plan.takeoff()), PathLog.pos(plan.landing()),
                PathLog.num(plan.blocks()), PathLog.body(rig.entity));
    }

    @Override
    public Driver.State state() {
        return state;
    }

    @Override
    public Halt halt() {
        return halt;
    }

    @Override
    public Driver.State tick() {
        if (state != Driver.State.RUNNING) {
            return state;
        }
        ticks++;
        if (phase == Phase.TAKEOFF) {
            takeoff();
        }
        if (phase == Phase.GLIDE && state == Driver.State.RUNNING) {
            glide();
        }
        return state;
    }

    // ==================== 起飞 ====================

    /**
     * 平地起飞:先把视线转到爬升的方向(颈部转得有速度,烟花是朝视线推的,脸没转过来就放等于白放),转到了起跳、松开、离地后再按一下展开。
     * 从边沿走出去:视线转到下滑的方向、平视,按住前进与疾跑走出边沿,离地后新按一下跳展开(不起跳、不放烟花)。
     * 转头、起跳到展开总共有时限,展不开就放弃。
     */
    private void takeoff() {
        var body = rig.entity;
        if (body.isFallFlying()) {
            rig.keys.release(Key.JUMP);
            rig.keys.release(Key.FORWARD);
            rig.keys.release(Key.SPRINT);
            phase = Phase.GLIDE;
            return;
        }
        boolean drop = plan.drop();
        if (aligning == 0 && Snapshots.mobility(body).fireworks() > 0) {
            Hotbar.Grip grip = rig.hotbar.grip(Items.FIREWORK_ROCKET);
            rig.act(grip.action());
            if (!grip.ready() && !drop) {
                abort(plan.takeoff(), "手上拿不到烟花");
                return;
            }
        }
        Vec3 aim = way.at(Math.min(way.length(), 8));
        float yaw = Look.yawOf(aim.x - body.getX(), aim.z - body.getZ());
        float pitch = plan.pitch();
        rig.look.steer(yaw, pitch);
        rig.keys.release(Key.SNEAK);
        aligning++;
        boolean facing = Math.abs(Mth.wrapDegrees(yaw - body.getYRot())) <= ALIGNED && Math.abs(pitch - body.getXRot()) <= ALIGNED;
        if (takeoffTicks == 0 && !facing) {
            rig.keys.release(Key.FORWARD);
            if (aligning > ALIGN_LIMIT) {
                abort(plan.takeoff(), "视线转不到起飞的方向");
            }
            return;
        }
        if (takeoffTicks++ > TAKEOFF_LIMIT) {
            abort(plan.takeoff(), "展开不了鞘翅");
            return;
        }
        if (drop) {
            rig.keys.press(Key.FORWARD);
            rig.keys.press(Key.SPRINT);
        } else {
            rig.keys.release(Key.FORWARD);
        }
        if (!drop && takeoffTicks == 1) {
            rig.keys.press(Key.JUMP);
        } else if (!drop && takeoffTicks == 2) {
            rig.keys.release(Key.JUMP);
        } else if (!body.onGround() && (pressedAt < 0 || takeoffTicks - pressedAt > UNFURL_RETRY)) {
            // 离地之后新按一下跳:按下沿,原版客户端在这时发展开的指令
            rig.keys.release(Key.JUMP);
            rig.keys.press(Key.JUMP);
            pressedAt = takeoffTicks;
        } else {
            rig.keys.release(Key.JUMP);
        }
    }

    // ==================== 飞 ====================

    private void glide() {
        var body = rig.entity;
        if (body.onGround() && !body.isFallFlying()) {
            land();
            return;
        }
        Mobility mobility = Snapshots.mobility(body);
        s = way.project(body.position(), s, PROGRESS_REACH);
        // 烟花少于下限(计划指望着烟花才这么算,不放烟花也飞得到的计划不在此列)或鞘翅耐久低于下限,而够不着降落点:就近降落,先朝目标方向找
        boolean low = !plan.drop() && mobility.fireworks() < Glide.ROCKET_FLOOR || mobility.gliderLife() < Glide.WEAR_FLOOR;
        if (!emergency && !loitering && low && !reaches(body.position(), way.touchdown())
                && !redirect("烟花或鞘翅耐久低于下限,够不着降落点")) {
            loiter();
        }
        if (loitering && ticks - loiterSince > LOITER_LIMIT) {
            loitering = false;
            way = plan.way();
            s = way.project(body.position(), 0);
        }
        if (loitering && ticks % LOITER_RETRY == 0 && redirect("盘旋中找到了够得着的地面")) {
            loitering = false;
        }
        if (!plan.known() && !settled && !emergency && !loitering && ticks % SETTLE_EVERY == 0) {
            // 目标那片先没加载:飞到哪加载到哪,加载了就定真正的降落点,从此刻的位置重排下滑的线
            Airway next = Glide.settle(rig, plan, body.position());
            if (next != null) {
                settled = true;
                way = next;
                s = 0;
                helm.forget();
                PathLog.info("{} 目标那片加载了,定下降落点 {} 身体 {}", rig.who, PathLog.pos(BlockPos.containing(next.touchdown())), PathLog.body(body));
            }
        }
        Beat beat = helm.steer(way, s);
        BlockPos hit = !emergency && !loitering ? helm.blocked(way, s) : null;
        if (hit != null) {
            // 照这条线飞下去会撞(前方飞到哪加载到哪,新加载的那段里有东西):先抬高巡航重排,抬不过去再趁还有高度挑一处够得着的地面降下去
            Airway up = Glide.raise(rig, way, body.position());
            if (up != null) {
                way = up;
                s = 0;
                helm.forget();
                PathLog.info("{} 前方有东西挡着({}),抬高巡航重排 {}", rig.who, blocker(hit), PathLog.body(body));
            } else {
                redirect("前方有东西挡着:" + blocker(hit));
            }
        } else if (beat instanceof Beat.Blocked blocked && !emergency && !loitering) {
            // 一刻都飞不了:改降到够得着的地面;已经在迫降还被围住,身体已在空中,别无他法,键松着等落地(落地的伤害如实记账)
            redirect("前方推演全被否决,挡路的是 " + blocker(blocked.blockage().cell()));
        }
    }

    /** 日志里挡路的那一格:坐标与方块。 */
    private String blocker(BlockPos cell) {
        return PathLog.pos(cell) + " " + PathLog.block(rig.world().getBlockState(cell));
    }

    /** 落地处就在计划的降落点那一圈里,不是迫降:这一段飞到了该到的地方。 */
    boolean reached() {
        return !emergency && Math.hypot(rig.entity.getX() - way.touchdown().x, rig.entity.getZ() - way.touchdown().z) <= Glide.TOLERANCE;
    }

    /** 这一段从哪个起飞点起的。 */
    BlockPos takeoffCell() {
        return plan.takeoff();
    }

    /** 从 {@code from} 不放烟花滑得到 {@code to}(按滑翔比留余量)。 */
    private static boolean reaches(Vec3 from, Vec3 to) {
        return (from.y - to.y) * Glide.GLIDE_RATIO * REACH_MARGIN >= Math.hypot(to.x - from.x, to.z - from.z);
    }

    /**
     * 迫降:挑一处够得着、站得住的地面,改沿从此刻的位置直接下滑到那里的线飞。先顺着此刻飞的方向找(不转弯,下滑最平:从远到近,偏一点也行),
     * 没有再往四周找(离原来的降落点近的优先)。一处也没有为 false(照原来的线飞,或被围住就放弃)。
     */
    private boolean redirect(String why) {
        var body = rig.entity;
        BlockPos best = ahead();
        if (best == null) {
            best = around();
        }
        if (best == null) {
            return false;
        }
        Vec3 spot = new Vec3(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        // 地面只知道落点这一格的高度:以它为准的一圈里落地都算数(迫降不求落在那一格上,落在它前头的平地上同样行)
        way = Airway.through(List.of(body.position(), spot), spot).withZone(EMERGENCY_ZONE);
        s = 0;
        emergency = true;
        loitering = false;
        helm.forget();
        PathLog.info("{} 迫降({}) 改降在 {} {}", rig.who, why, PathLog.pos(best), PathLog.body(body));
        return true;
    }

    /**
     * 找不到够得着的落点:绕着前方的一点盘旋(同一个控制器跟一条圆形的线,有烟花就靠烟花撑住高度),隔一阵再找一次落点。
     * 盘旋只是等,不是办法:盘旋 {@link #LOITER_LIMIT} 刻还没有落点,就照原来的线飞(落点够不着也只能那样落下去)。
     */
    private void loiter() {
        var body = rig.entity;
        Vec3 v = body.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        double ux = speed > 0.05 ? v.x / speed : -Math.sin(Math.toRadians(body.getYRot()));
        double uz = speed > 0.05 ? v.z / speed : Math.cos(Math.toRadians(body.getYRot()));
        Vec3 center = body.position().add(ux * LOITER_RADIUS, 0, uz * LOITER_RADIUS);
        way = Airway.circle(body.position(), center, LOITER_RADIUS, body.getY(), 3);
        s = 0;
        loitering = true;
        loiterSince = ticks;
        helm.forget();
        PathLog.info("{} 找不到够得着的落点,盘旋等 {}", rig.who, PathLog.body(body));
    }

    /** 够得着(留余量)、直线通得过去、不用俯冲的落点 {@code spot}。 */
    private boolean landable(Vec3 spot) {
        var body = rig.entity;
        return reaches(body.position(), spot)
                && Math.hypot(spot.x - body.getX(), spot.z - body.getZ()) >= (body.getY() - spot.y) * MIN_RATIO
                && Airway.sweep(rig.world(), rig.snapshot().stats(), 0).clear(body.position(), spot.add(0, 1, 0));
    }

    /**
     * 顺着此刻水平飞的方向找落点:先不转弯,再往左右各转一点({@link #TURNS} 度,转得越少越先),每个方向从远到近(下滑最平的先)。
     * 滑翔转弯转得慢(颈部有加速度上限,速度又快),往回掉头的落点来不及,不在这里找。
     */
    private BlockPos ahead() {
        var body = rig.entity;
        Vec3 v = body.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        double ux = speed > 0.05 ? v.x / speed : -Math.sin(Math.toRadians(body.getYRot()));
        double uz = speed > 0.05 ? v.z / speed : Math.cos(Math.toRadians(body.getYRot()));
        BlockPos under = Glide.ground(rig, rig.snapshot().stats(), body.getBlockX(), body.getBlockZ(), body.getBlockY(), rig.world().getMinBuildHeight());
        if (under == null) {
            return null;
        }
        double altitude = body.getY() - under.getY();
        for (int turn : TURNS) {
            double angle = Math.toRadians(turn);
            double dx = ux * Math.cos(angle) - uz * Math.sin(angle);
            double dz = uz * Math.cos(angle) + ux * Math.sin(angle);
            for (double d = altitude * Glide.GLIDE_RATIO * REACH_MARGIN; d >= altitude * MIN_RATIO; d -= 1.0) {
                int x = (int) Math.floor(body.getX() + dx * d);
                int z = (int) Math.floor(body.getZ() + dz * d);
                BlockPos cell = Glide.ground(rig, rig.snapshot().stats(), x, z, body.getBlockY(), rig.world().getMinBuildHeight());
                if (cell != null && landable(new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5))) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** 迫降找落点时往左右转的角度(度),转得少的先试。 */
    private static final int[] TURNS = {0, 15, -15, 30, -30, 45, -45, 60, -60};

    /** 往四周找落点(不找身后的:掉头来不及),离原来的降落点近的优先;没有为 null。 */
    private BlockPos around() {
        var body = rig.entity;
        ServerLevel level = body.serverLevel();
        BlockPos at = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -EMERGENCY_RADIUS; dx <= EMERGENCY_RADIUS; dx += EMERGENCY_STEP) {
            for (int dz = -EMERGENCY_RADIUS; dz <= EMERGENCY_RADIUS; dz += EMERGENCY_STEP) {
                int x = at.getX() + dx;
                int z = at.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                BlockPos cell = Glide.ground(rig, rig.snapshot().stats(), x, z, at.getY(), rig.world().getMinBuildHeight());
                if (cell == null) {
                    continue;
                }
                Vec3 spot = new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                double near = Math.hypot(spot.x - plan.way().touchdown().x, spot.z - plan.way().touchdown().z);
                boolean behind = (spot.x - body.getX()) * body.getDeltaMovement().x + (spot.z - body.getZ()) * body.getDeltaMovement().z < 0;
                if (!behind && near < bestDistance && landable(spot)) {
                    best = cell;
                    bestDistance = near;
                }
            }
        }
        return best;
    }

    // ==================== 收场 ====================

    private void land() {
        var body = rig.entity;
        record(false);
        rig.keys.releaseAll();
        state = Driver.State.ARRIVED;
        PathLog.info("{} 滑翔落地 {} 用了 {} 刻 放了 {} 支烟花 {}", rig.who, PathLog.pos(body.blockPosition()), ticks, helm.fired(), PathLog.body(body));
    }

    private void abort(BlockPos cell, String why) {
        PathLog.info("{} 滑翔中止:{} {}", rig.who, why, PathLog.body(rig.entity));
        record(!rig.entity.onGround());
        rig.keys.releaseAll();
        halt = new Halt.Blocked(new Blockage(cell, rig.world().getBlockState(cell), com.dwinovo.numen.pathing.plan.MoveKind.FLY, null,
                Blockage.Hitch.STUCK));
        state = Driver.State.HALTED;
    }

    /** 记一笔账:没起飞(没离地)也记,模型要知道这一趟没飞成。 */
    private void record(boolean airborne) {
        var body = rig.entity;
        ItemStack chest = body.getItemBySlot(EquipmentSlot.CHEST);
        Vec3 now = body.position();
        double blocks = Math.hypot(now.x - launchedAt.x, now.z - launchedAt.z);
        rig.ledger.glided(new EditLedger.Glided(plan.takeoff(), body.blockPosition(), blocks, helm.fired(), wearBefore, chest.getDamageValue(),
                Math.max(0, healthBefore - body.getHealth()), emergency, airborne));
    }

    // ==================== 段的接口 ====================

    @Override
    public boolean progressing() {
        return true;
    }

    @Override
    public boolean waiting() {
        return false;
    }

    @Override
    public List<Route.Leg> remaining() {
        return List.of();
    }

    /** 滑翔着(离地)是计划内的下落。 */
    @Override
    public boolean plannedFall() {
        return state == Driver.State.RUNNING && !rig.entity.onGround();
    }

    @Override
    public boolean plannedDive() {
        return false;
    }

    @Override
    public void retarget(Goal goal) {
    }

    @Override
    public void stop() {
        rig.keys.releaseAll();
        if (state == Driver.State.RUNNING) {
            record(!rig.entity.onGround());
            state = Driver.State.HALTED;
        }
    }

    @Override
    public Blockage nearestRefusal() {
        return null;
    }
}
