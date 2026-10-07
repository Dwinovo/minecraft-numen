package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;

import net.minecraft.server.level.ServerPlayer;

/**
 * 执行的一套家伙:身体与它的键盘、动手的端口、活世界、宿主的另外几个端口,以及这次导航的实际账、身体动作与潜过的水。一次导航
 * 一份,段状态机与每一步的控制器共用,账记在一处。
 */
final class Rig {

    final Body body;
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
    /** 这次导航里身体真在水下憋过的气。 */
    final DiveLog dives = new DiveLog();
    /** 日志里的"谁"({@link PathLog#who})。 */
    final String who;
    /** 这一刻主线程上寻路用了多久。 */
    final TickTally tally = new TickTally();
    private final List<BodyAction> actions = new ArrayList<>();
    private LiveWorld world;

    Rig(Body body, TerrainPolicy terrain, Materials materials, Threats threats) {
        this.body = body;
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

    /** 右键这一下;用了多久记进这一刻的账。 */
    Mouse.Use use() {
        long t0 = System.nanoTime();
        try {
            return mouse.use();
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
