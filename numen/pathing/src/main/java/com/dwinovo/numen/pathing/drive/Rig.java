package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.api.Travel;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;

import com.dwinovo.numen.api.entity.Neck;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 执行的一套家伙:身体与它的键盘、动手的端口、活世界、宿主的另外几个端口,以及这次导航的实际账、身体动作与潜过的水。一次导航
 * 一份,段状态机与每一步的控制器共用,账记在一处。
 */
final class Rig implements Craft {

    final Body body;
    /** 这一趟怎么走:只有创造飞行的一趟读完整的快照,走与滑翔的一趟身体的快照按没有飞行能力算({@link BodySnapshot#afoot})。 */
    final Travel travel;
    final ServerPlayer entity;
    /** 身体的键盘(一具身体一副,宿主每刻在身体的物理步进里落一次)。 */
    final Controls keys;
    /** 身体的视角。 */
    final Look look;
    /** 身体的快捷栏。 */
    final Hotbar hotbar;
    /** 身体的鼠标:准星、左键挖、右键用,动手之前过不过权限层它自己管。 */
    final Mouse mouse;
    final Materials materials;
    final TerrainPolicy terrain;
    final Threats threats;
    final EditLedger ledger = new EditLedger();
    /** 这次导航一路上的搜索、重搜、走不下去、卡住与复核的次数。 */
    final Journal journal = new Journal();
    /** 这次导航里身体真在水下憋过的气。 */
    final DiveLog dives = new DiveLog();
    /** 日志里的"谁"({@link PathLog#who})。 */
    final String who;
    /** 这一刻主线程上寻路用了多久。 */
    final TickTally tally = new TickTally();
    private final List<BodyAction> actions = new ArrayList<>();
    private LiveWorld world;

    Rig(Body body, Travel travel, TerrainPolicy terrain, Materials materials, Threats threats) {
        this.body = body;
        this.travel = travel;
        this.entity = body.entity();
        this.keys = body.controls();
        this.look = body.look();
        this.hotbar = body.hotbar();
        this.mouse = body.mouse();
        this.terrain = terrain;
        this.materials = materials;
        this.threats = threats;
        this.who = PathLog.who(entity);
    }

    /** 左键这一下;用了多久(问许可、原版挖掘与它引起的方块更新)记进这一刻的账。 */
    Mouse.Strike dig() {
        long t0 = System.nanoTime();
        try {
            return mouse.dig();
        } finally {
            tally.acted(System.nanoTime() - t0);
        }
    }

    /** 右键这一下(走路的右键:放下的方块记成垫路);用了多久记进这一刻的账。 */
    Mouse.Use use() {
        long t0 = System.nanoTime();
        try {
            return mouse.usePath();
        } finally {
            tally.acted(System.nanoTime() - t0);
        }
    }

    /** 身体此刻所在的活世界(换了维度就换一份)。 */
    @Override
    public LiveWorld world() {
        if (world == null || world.level() != entity.level()) {
            world = new LiveWorld(entity.level());
        }
        return world;
    }

    @Override
    public BodySnapshot snapshot() {
        return travel == Travel.FLY ? body.snapshot() : body.snapshot().afoot();
    }

    // ==================== 空中的身体({@link Craft}) ====================

    @Override
    public String who() {
        return who;
    }

    @Override
    public String describe() {
        return PathLog.body(entity);
    }

    @Override
    public Mobility mobility() {
        return Snapshots.mobility(entity);
    }

    @Override
    public Vec3 position() {
        return entity.position();
    }

    @Override
    public Vec3 velocity() {
        return entity.getDeltaMovement();
    }

    @Override
    public BlockPos cell() {
        return entity.blockPosition();
    }

    @Override
    public AABB box() {
        return entity.getBoundingBox();
    }

    @Override
    public boolean onGround() {
        return entity.onGround();
    }

    @Override
    public boolean gliding() {
        return entity.isFallFlying();
    }

    @Override
    public float yaw() {
        return entity.getYRot();
    }

    @Override
    public float pitch() {
        return entity.getXRot();
    }

    @Override
    public double eyeHeight() {
        return entity.getEyeHeight();
    }

    @Override
    public double gravity() {
        return entity.getGravity();
    }

    @Override
    public double fallen() {
        return entity.fallDistance;
    }

    @Override
    public float health() {
        return entity.getHealth();
    }

    @Override
    public int wear() {
        return entity.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
    }

    @Override
    public Controls keys() {
        return keys;
    }

    @Override
    public void steer(float yaw, float pitch) {
        look.steer(yaw, pitch);
    }

    @Override
    public Neck neck() {
        return look.neck();
    }

    @Override
    public boolean holdRocket() {
        Hotbar.Grip grip = hotbar.grip(Items.FIREWORK_ROCKET);
        act(grip.action());
        return grip.ready();
    }

    @Override
    public boolean launchRocket() {
        return mouse.useItem() instanceof Mouse.Use.Pressed;
    }

    @Override
    public EditLedger ledger() {
        return ledger;
    }

    /** 记下身体做的一个动作;null 是什么也没做。 */
    void act(BodyAction action) {
        if (action == null) {
            return;
        }
        actions.add(action);
        PathLog.debug("{} 身体动作 {}", who, action);
    }

    List<BodyAction> actions() {
        return Collections.unmodifiableList(actions);
    }
}
