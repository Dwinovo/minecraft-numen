package com.dwinovo.numen.pathing.drive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 滑翔的一段:从起飞点起跳、空中展开鞘翅、放烟花爬升,沿计划的线飞({@link Glide.Plan}),进入目标 {@link Glide#DESCENT_RANGE} 格就降落,落地。合法执行:只按视角(颈部模型)、
 * 跳(按下沿展开)、右键烟花,不改速度、坐标、计时器,不发原版客户端不会发的包;不替她穿鞘翅、不去箱子里拿烟花(身上有什么就用什么)。
 *
 * <ul>
 *   <li><b>起飞</b>:先把烟花拿到手上(换手记进身体动作),抬头朝巡航方向,起跳,松开,离地之后再按一下跳(新的按下沿)展开;展开了就是滑翔;</li>
 *   <li><b>飞</b>:局部控制器({@link Pilot},物理 {@link Dynamics#gliding})沿线飞({@link Helm}),烟花够用才放;撞墙的推演被否决;前方被挡就沿航线往前拉升({@link Glide#raise});</li>
 *   <li><b>降落</b>:水平进入目标 {@link Glide#DESCENT_RANGE} 格就挑落点({@link Glide#descent}:只求安全,先正下方再由近到远),一直降到落地为止;
 *       降的途中落点落不下了(被占、被挡、够不着),拉黑它换下一个。一个安全的落点也没有,就绕着盘旋等,盘旋有时限,到了时限收场;</li>
 *   <li><b>迫降</b>:没进目标范围就得落地(烟花或耐久低于下限、前方被挡拉不高),同一套选点,照实记一笔(迫降);</li>
 *   <li><b>卡住</b>:滑翔着身体一秒没离开原地({@link Watchdog},走路判卡住的同一个,滑翔用大一点的尺子),就是被方块顶住了:松开所有键收场,交出顶着身体的那一格,不替她挖;</li>
 *   <li><b>落地</b>:脚着地、滑翔停了,按住潜行直到水平速度消失。落地就是这一段的结束,不再走最后一截。收场记一笔账({@link EditLedger.Glided}):起降的位置、飞了多远、
 *       用了几支烟花、鞘翅耐久前后、落地伤了几点血。</li>
 * </ul>
 */
final class Sortie implements Segment {

    private enum Phase { TAKEOFF, GLIDE, SETTLE }

    /** 视线离爬升方向不到这么多度(两个方向)才起跳;转头最多几刻。 */
    private static final float ALIGNED = 4.0F;
    private static final int ALIGN_LIMIT = 60;
    /** 起跳之后最多几刻,展开不了就放弃。 */
    private static final int TAKEOFF_LIMIT = 30;
    /** 展开那一下跳之后几刻还没展开,就再按一次。 */
    private static final int UNFURL_RETRY = 3;
    /** 盘旋:半径、每隔几刻再找一次落点、最多盘旋几刻。 */
    private static final double LOITER_RADIUS = 14.0;
    private static final int LOITER_RETRY = 10;
    private static final int LOITER_LIMIT = 400;
    /** 离脚下的地面不到这么高(格)就不盘旋:圈兜不过来,贴着地面绕圈是往坡上撞。没有落点时照原来的线飞下去。 */
    private static final double LOITER_FLOOR = 8.0;
    /** 身体在线上的进度一刻往前最多找这么多格({@link Airway#project}):身体一刻飞不到这么远,留余量。 */
    private static final double PROGRESS_REACH = 8.0;
    /** 降落途中每隔几刻复核一次落点还落不落得下(算力预算:一次复核读周围九列)。 */
    private static final int SPOT_CHECK = 5;
    /** 落地后水平速度低于它就算停稳;最多按住潜行几刻。 */
    private static final double AT_REST = 0.01;
    private static final int SETTLE_LIMIT = 40;
    /** 顶着身体的方块:身体盒往外多看这么多格。 */
    private static final double TOUCH = 0.3;

    private final Rig rig;
    private final Glide.Plan plan;
    private final Helm helm;
    private final Watchdog watchdog = Watchdog.gliding();
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
    /** 已经转入降落阶段(进了目标范围,或迫降)。 */
    private boolean descending;
    /** 飞进过目标 {@link Glide#DESCENT_RANGE} 格:这之后落地就是到了。没进过就落地是迫降,落地后看要不要再起飞。 */
    private boolean descended;
    /** 此刻定的落点;还没挑或一个都没找到(盘旋等)为 null。 */
    private BlockPos spot;
    /** 这一段里落不下来、拉黑了的落点。 */
    private final Set<BlockPos> barred = new HashSet<>();
    /** 在盘旋等落点:从第几刻起。 */
    private boolean loitering;
    private int loiterSince;
    /** 被方块顶住,收了场。 */
    private boolean trapped;
    private int settleTicks;
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
        if (plan.drop()) {
            // 从边沿滑下去的计划自己就是降落:线的终点是安全的落点
            descending = true;
            spot = plan.landing();
        }
        PathLog.info("{} 滑翔 从 {} 朝 {} 水平 {} 格 {}", rig.who, PathLog.pos(plan.takeoff()), PathLog.num(plan.aim().x()) + "," + PathLog.num(plan.aim().z()),
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
        } else if (phase == Phase.SETTLE && state == Driver.State.RUNNING) {
            settle();
        }
        return state;
    }

    // ==================== 起飞 ====================

    /**
     * 平地起飞:先把视线转到爬升的方向(颈部转得有速度,烟花是朝视线推的,脸没转过来就放等于白放),转到了起跳、松开、离地后再按一下展开。
     * 许飞的身体(创造)按第一下跳之后的窗口里再按跳会被当成双击、切换成创造飞行({@link com.dwinovo.numen.api.entity.Controls#jumpWouldToggleFlight}),
     * 展开那一下要等窗口过去。
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
        } else if (!body.onGround() && (pressedAt < 0 || takeoffTicks - pressedAt > UNFURL_RETRY) && !rig.keys.jumpWouldToggleFlight()) {
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
        watchdog.observe(body.position(), false);
        if (!progressing()) {
            trap();
            return;
        }
        Mobility mobility = Snapshots.mobility(body);
        s = way.project(body.position(), s, PROGRESS_REACH);
        descended |= plan.aim().distance(body.position()) <= Glide.DESCENT_RANGE;
        if (!descending) {
            if (descended) {
                descend("进入目标 " + (int) Glide.DESCENT_RANGE + " 格,开始降落");
            } else if (mobility.fireworks() < Glide.ROCKET_FLOOR || mobility.gliderLife() < Glide.WEAR_FLOOR) {
                // 烟花或鞘翅耐久低于下限(巡航的线指望着烟花):就近降落
                descend("烟花或鞘翅耐久低于下限");
            }
        } else if (loitering) {
            if (ticks - loiterSince > LOITER_LIMIT) {
                abort(plan.takeoff(), "盘旋了 " + LOITER_LIMIT + " 刻也找不到安全的落点");
                return;
            }
            if (ticks % LOITER_RETRY == 0) {
                descend("盘旋中找到了安全的落点");
            }
        } else if (ticks % SPOT_CHECK == 0 && !landable()) {
            bar();
            descend("选好的落点落不下了");
        }
        Beat beat = helm.steer(way, s);
        if (loitering) {
            return;
        }
        BlockPos hit = helm.blocked(way, s);
        if (hit != null) {
            blockedAhead(hit);
        } else if (beat instanceof Beat.Blocked blocked) {
            // 一刻都飞不了:被围住,换一个落点(降落中)或就近降落
            bar();
            descend("前方推演全被否决,挡路的是 " + blocker(blocked.blockage().cell()));
        }
    }

    /**
     * 照这条线飞下去会撞(前方飞到哪加载到哪,新加载的那段里有东西,或落点那一路有东西):降落中,换一个落点;巡航中,在离目标还远(进目标范围之后降落自会接手)的地方
     * 先沿航线往前拉升重排,拉不过去再趁还有高度就近降落。
     */
    private void blockedAhead(BlockPos hit) {
        if (descending) {
            bar();
            descend("落点那一路有东西挡着:" + blocker(hit));
            return;
        }
        if (plan.aim().distance(Vec3.atCenterOf(hit)) <= Glide.DESCENT_RANGE) {
            return;
        }
        Airway up = Glide.raise(rig, plan, rig.entity.position());
        if (up != null) {
            way = up;
            s = 0;
            helm.forget();
            PathLog.info("{} 前方有东西挡着({}),沿航线往前拉升重排 {}", rig.who, blocker(hit), PathLog.body(rig.entity));
        } else {
            descend("前方有东西挡着:" + blocker(hit));
        }
    }

    /** 日志里挡路的那一格:坐标与方块。 */
    private String blocker(BlockPos cell) {
        return PathLog.pos(cell) + " " + PathLog.block(rig.world().getBlockState(cell));
    }

    /** 落地处是不是在这一段里降落到了:进入目标范围之后的降落。否则是迫降。 */
    boolean descended() {
        return descended;
    }

    /** 被方块顶住收的场。 */
    boolean trapped() {
        return trapped;
    }

    /** 这一段从哪个起飞点起的。 */
    BlockPos takeoffCell() {
        return plan.takeoff();
    }

    /** 此刻定的落点还是安全的落点:没被占、没被改。够不够得着是选点时({@link Glide#descent})的事。 */
    private boolean landable() {
        return spot != null && spot.equals(Landings.safe(rig, rig.snapshot().stats(), spot.getX(), spot.getZ()));
    }

    /** 此刻的落点落不下了,这一段里不再选它。 */
    private void bar() {
        if (spot != null) {
            barred.add(spot);
        }
    }

    /**
     * 转入降落(第一次)或换一个落点:从此刻的位置挑安全的、滑得到的落点({@link Glide#descent}),改沿往它去的线飞。第一次转入降落时,身体已进过目标范围是降落,
     * 不然是迫降。一处也没有就绕着前方的一点盘旋(同一个控制器跟一条圆形的线,有烟花就靠烟花撑住高度),隔一阵再找一次,盘旋有时限。
     */
    private void descend(String why) {
        var body = rig.entity;
        Glide.Descent next = Glide.descent(rig, body.position(), Snapshots.mobility(body), barred);
        descending = true;
        if (next == null) {
            spot = null;
            if (!loitering && body.getY() - Glide.top(rig, body.getBlockX(), body.getBlockZ()) >= LOITER_FLOOR) {
                loiter();
            }
            return;
        }
        spot = next.spot();
        way = next.way();
        s = 0;
        loitering = false;
        helm.forget();
        PathLog.info("{} {}({}) 落点 {} {}", rig.who, descended ? "降落" : "迫降", why, PathLog.pos(spot), PathLog.body(body));
    }

    /** 找不到安全的落点:绕着前方的一点盘旋,隔一阵再找一次。盘旋只是等,不是办法:到了时限就收场。 */
    private void loiter() {
        var body = rig.entity;
        Vec3 center = body.position().add(Glide.heading(body).scale(LOITER_RADIUS));
        way = Airway.circle(body.position(), center, LOITER_RADIUS, body.getY(), 3);
        s = 0;
        loitering = true;
        loiterSince = ticks;
        helm.forget();
        PathLog.info("{} 找不到安全的落点,盘旋等 {}", rig.who, PathLog.body(body));
    }

    // ==================== 收场 ====================

    /** 脚着地:记账,按住潜行等水平速度消失({@link #settle})。 */
    private void land() {
        var body = rig.entity;
        record(false);
        rig.keys.releaseAll();
        phase = Phase.SETTLE;
        PathLog.info("{} 滑翔落地 {} 用了 {} 刻 放了 {} 支烟花 {}", rig.who, PathLog.pos(body.blockPosition()), ticks, helm.fired(), PathLog.body(body));
    }

    /** 落地之后按住潜行,直到水平速度消失(最多 {@link #SETTLE_LIMIT} 刻);然后这一段就结束了。 */
    private void settle() {
        Vec3 v = rig.entity.getDeltaMovement();
        if (Math.hypot(v.x, v.z) < AT_REST || ++settleTicks > SETTLE_LIMIT) {
            rig.keys.releaseAll();
            state = Driver.State.ARRIVED;
        } else {
            rig.keys.press(Key.SNEAK);
        }
    }

    /**
     * 滑翔着被方块顶住({@link Watchdog} 一秒没见身体挪动):松开所有键,收场,交出顶着身体的那一格。她能自己挖开({@code numen.work.dig}),
     * 所以只报告,不替她挖;烟花放了几支、耐久耗了多少记在账上。
     */
    private void trap() {
        var body = rig.entity;
        BlockPos cell = touching();
        trapped = true;
        PathLog.info("{} 滑翔卡住:身体一秒没离开原地,顶着它的是 {} 放了 {} 支烟花 {}", rig.who, blocker(cell), helm.fired(), PathLog.body(body));
        record(true);
        rig.keys.releaseAll();
        halt = new Halt.Blocked(new Blockage(cell, rig.world().getBlockState(cell), com.dwinovo.numen.pathing.plan.MoveKind.FLY, null, Blockage.Hitch.PINNED));
        state = Driver.State.HALTED;
    }

    /** 顶着身体的方块:身体盒往外 {@link #TOUCH} 格内离身体最近的有碰撞的一格;没有为 null。 */
    private BlockPos touching() {
        var body = rig.entity;
        AABB box = body.getBoundingBox().inflate(TOUCH);
        BlockPos nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (rig.world().getBlockState(pos).getCollisionShape(body.level(), pos).isEmpty()) {
                continue;
            }
            double d = pos.distToCenterSqr(body.position());
            if (d < best) {
                best = d;
                nearest = pos.immutable();
            }
        }
        return nearest;
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
                Math.max(0, healthBefore - body.getHealth()), descending && !descended, airborne, trapped));
    }

    // ==================== 段的接口 ====================

    /**
     * 在推进:滑翔着身体在离开原地({@link Watchdog},与走路同一个判据);起飞的几刻另有时限。没离开原地、身边又没有顶着它的方块,不算卡住:那是在空中慢下来盘旋,
     * 控制器自己会改,身体也会落下去。
     */
    @Override
    public boolean progressing() {
        return phase != Phase.GLIDE || watchdog.progressing() || touching() == null;
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
            if (phase != Phase.SETTLE) {
                record(!rig.entity.onGround());
            }
            state = Driver.State.HALTED;
        }
    }

    @Override
    public Blockage nearestRefusal() {
        return null;
    }
}
