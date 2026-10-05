package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 执行的一套家伙:身体与它的键盘、动手的端口、活世界、宿主的另外几个端口,以及这次导航的实际账、身体动作与潜过的水。一次导航
 * 一份,段状态机与每一步的控制器共用,账记在一处。
 */
final class Rig {

    final Body body;
    final ServerPlayer entity;
    /** 身体的键盘(一具身体一副,宿主每刻在身体的物理步进里落一次)。 */
    final Controls keys;
    final Effector hands;
    final Materials materials;
    final TerrainPolicy terrain;
    final Threats threats;
    final EditLedger ledger = new EditLedger();
    /** 这次导航里身体真在水下憋过的气。 */
    final DiveLog dives = new DiveLog();
    /** 日志里的"谁"({@link PathLog#who})。 */
    final String who;
    /** 这一刻主线程上寻路用了多久。 */
    final TickTally tally = new TickTally();
    private final List<BodyAction> actions = new ArrayList<>();
    private LiveWorld world;

    Rig(Body body, Effector hands, TerrainPolicy terrain, Materials materials, Threats threats) {
        this.body = body;
        this.entity = body.entity();
        this.keys = body.controls();
        this.hands = hands;
        this.terrain = terrain;
        this.materials = materials;
        this.threats = threats;
        this.who = PathLog.who(entity);
    }

    /** 经宿主的手左键这一下;用了多久(宿主问许可、原版挖掘与它引起的方块更新)记进这一刻的账。 */
    Effector.Strike dig(BlockHitResult hit) {
        long t0 = System.nanoTime();
        try {
            return hands.dig(hit);
        } finally {
            tally.acted(System.nanoTime() - t0);
        }
    }

    /** 经宿主的手右键这一下;用了多久记进这一刻的账。 */
    Effector.Use use(BlockHitResult hit) {
        long t0 = System.nanoTime();
        try {
            return hands.use(hit);
        } finally {
            tally.acted(System.nanoTime() - t0);
        }
    }

    /** 身体此刻所在的活世界(换了维度就换一份)。 */
    LiveWorld world() {
        if (world == null || world.level() != entity.serverLevel()) {
            world = new LiveWorld(entity.serverLevel());
        }
        return world;
    }

    BodySnapshot snapshot() {
        return body.snapshot();
    }

    /** 记下身体做的一个动作;null 是什么也没做。 */
    void act(BodyAction action) {
        if (action == null) {
            return;
        }
        actions.add(action);
        if (action instanceof BodyAction.Dismounted) {
            PathLog.info("{} 下载具 {} {}", who, action, PathLog.body(entity));
        } else {
            PathLog.debug("{} 身体动作 {}", who, action);
        }
    }

    List<BodyAction> actions() {
        return Collections.unmodifiableList(actions);
    }
}
