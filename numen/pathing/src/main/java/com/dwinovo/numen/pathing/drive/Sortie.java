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
    /** 迫降:往下最多找多少格的地面。 */
    private static final int EMERGENCY_DROP = 60;

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
    private final int wearBefore;
    private final float healthBefore;
    private final Vec3 launchedAt;

    Sortie(Rig rig, Glide.Plan plan) {
        this.rig = rig;
        this.plan = plan;
        this.way = plan.way();
        this.helm = new Helm(rig, Dynamics.gliding(rig.entity.getGravity(), rig.snapshot().mobility().rocketBurn()));
        this.wearBefore = rig.entity.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
        this.healthBefore = rig.entity.getHealth();
        this.launchedAt = rig.entity.position();
        PathLog.info("{} 滑翔 从 {} 到 {} 水平 {} 格 估 {} 刻 烟花约 {} 支 鞘翅约 {} 点 {}", rig.who, PathLog.pos(plan.takeoff()), PathLog.pos(plan.landing()),
                PathLog.num(plan.blocks()), plan.ticks(), plan.rockets(), plan.wear(), PathLog.body(rig.entity));
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
        if (ticks > plan.ticks() * 2 + 200 && !rig.entity.isFallFlying()) {
            abort(rig.entity.blockPosition(), "滑翔超时");
            return state;
        }
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
     * 先把视线转到爬升的方向(颈部转得有速度,烟花是朝视线推的,起飞时脸不朝对就白放),转到了起跳、松开、离地后再按一下展开。
     * 转头、起跳到展开总共有时限,展不开就放弃。
     */
    private void takeoff() {
        var body = rig.entity;
        if (body.isFallFlying()) {
            rig.keys.release(Key.JUMP);
            phase = Phase.GLIDE;
            return;
        }
        if (aligning == 0) {
            Hotbar.Grip grip = rig.hotbar.grip(Items.FIREWORK_ROCKET);
            rig.act(grip.action());
            if (!grip.ready()) {
                abort(plan.takeoff(), "手上拿不到烟花");
                return;
            }
        }
        Vec3 aim = way.at(Math.min(way.length(), 8));
        float yaw = Look.yawOf(aim.x - body.getX(), aim.z - body.getZ());
        rig.look.steer(yaw, Glide.CLIMB_PITCH);
        rig.keys.release(Key.FORWARD);
        rig.keys.release(Key.SNEAK);
        aligning++;
        if (takeoffTicks == 0 && (Math.abs(Mth.wrapDegrees(yaw - body.getYRot())) > ALIGNED || Math.abs(Glide.CLIMB_PITCH - body.getXRot()) > ALIGNED)) {
            if (aligning > ALIGN_LIMIT) {
                abort(plan.takeoff(), "视线转不到爬升的方向");
            }
            return;
        }
        if (takeoffTicks++ > TAKEOFF_LIMIT) {
            abort(plan.takeoff(), "展开不了鞘翅");
            return;
        }
        if (takeoffTicks == 1) {
            rig.keys.press(Key.JUMP);
        } else if (takeoffTicks == 2) {
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
        s = way.project(body.position(), s);
        if (!emergency && mobility.fireworks() == 0 && !reaches(body.position(), way.touchdown())) {
            redirect("烟花用完了,够不着降落点");
        }
        Beat beat = helm.steer(way, s);
        if (!emergency && helm.warned()) {
            // 照这条线飞下去,松开键滑出去就撞:趁还有高度,先挑一处够得着的地面降下去
            redirect("前方有东西挡着");
        } else if (beat instanceof Beat.Blocked && !emergency) {
            // 一刻都飞不了:改降到够得着的地面;已经在迫降还被围住,身体已在空中,别无他法,键松着等落地(落地的伤害如实记账)
            redirect("前方推演全被否决");
        }
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
        way = Airway.through(List.of(body.position(), spot), spot);
        s = 0;
        emergency = true;
        helm.forget();
        PathLog.info("{} 迫降({}) 改降在 {} {}", rig.who, why, PathLog.pos(best), PathLog.body(body));
        return true;
    }

    /** 够得着(留余量)、直线通得过去、不用俯冲的落点 {@code spot}。 */
    private boolean landable(Vec3 spot) {
        var body = rig.entity;
        return reaches(body.position(), spot)
                && Math.hypot(spot.x - body.getX(), spot.z - body.getZ()) >= (body.getY() - spot.y) * MIN_RATIO
                && Airway.sweep(rig.world(), rig.snapshot().stats(), 0).clear(body.position(), spot.add(0, 1, 0));
    }

    /** 顺着此刻水平飞的方向,从远到近、偏左右各几格找落点;没有为 null。 */
    private BlockPos ahead() {
        var body = rig.entity;
        Vec3 v = body.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        double ux = speed > 0.05 ? v.x / speed : -Math.sin(Math.toRadians(body.getYRot()));
        double uz = speed > 0.05 ? v.z / speed : Math.cos(Math.toRadians(body.getYRot()));
        BlockPos under = Glide.ground(rig, rig.snapshot().stats(), body.getBlockX(), body.getBlockZ(), body.getBlockY(), body.getBlockY() - EMERGENCY_DROP);
        if (under == null) {
            return null;
        }
        double altitude = body.getY() - under.getY();
        for (double d = altitude * Glide.GLIDE_RATIO * REACH_MARGIN; d >= altitude * MIN_RATIO; d -= 1.0) {
            for (int side : new int[] {0, -2, 2, -4, 4}) {
                int x = (int) Math.floor(body.getX() + ux * d - uz * side);
                int z = (int) Math.floor(body.getZ() + uz * d + ux * side);
                BlockPos cell = Glide.ground(rig, rig.snapshot().stats(), x, z, body.getBlockY(), body.getBlockY() - EMERGENCY_DROP);
                if (cell != null && landable(new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5))) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** 往四周找落点,离原来的降落点近的优先;没有为 null。 */
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
                BlockPos cell = Glide.ground(rig, rig.snapshot().stats(), x, z, at.getY(), at.getY() - EMERGENCY_DROP);
                if (cell == null) {
                    continue;
                }
                Vec3 spot = new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                double near = Math.hypot(spot.x - plan.way().touchdown().x, spot.z - plan.way().touchdown().z);
                if (near < bestDistance && landable(spot)) {
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
                Math.max(0, healthBefore - body.getHealth()), emergency, airborne, plan.rockets(), plan.ticks()));
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
