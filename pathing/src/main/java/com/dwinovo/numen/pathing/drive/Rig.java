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

/**
 * 执行的一套家伙:身体与它的键盘、动手的端口、活世界、宿主的另外几个端口,以及这次导航的实际账与身体动作。一次导航
 * 一份,段状态机与每一步的控制器共用;子导航(事后撤回垫块时走过去)也用同一份,账记在一处。
 */
final class Rig {

    final Body body;
    final ServerPlayer entity;
    final Controls keys = new Controls();
    final Effector hands;
    final Materials materials;
    final TerrainPolicy terrain;
    final Threats threats;
    final EditLedger ledger = new EditLedger();
    private final List<BodyAction> actions = new ArrayList<>();
    private LiveWorld world;

    Rig(Body body, Effector hands, TerrainPolicy terrain, Materials materials, Threats threats) {
        this.body = body;
        this.entity = body.entity();
        this.hands = hands;
        this.terrain = terrain;
        this.materials = materials;
        this.threats = threats;
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
        if (action != null) {
            actions.add(action);
        }
    }

    List<BodyAction> actions() {
        return Collections.unmodifiableList(actions);
    }
}
