package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;

/**
 * 落地的一段:走路的一趟开始时身体正在飞(创造飞行),先按住潜行垂直降下去,落地原版就停飞({@code Controls#afterTick}),
 * 走路的段从那里起搜。降到地上是身体自己做的事,账上记一笔({@link EditLedger#alighted}),回执说先落了地。
 */
final class Alight implements Segment {

    private final Rig rig;
    private Driver.State state = Driver.State.RUNNING;

    Alight(Rig rig) {
        this.rig = rig;
    }

    @Override
    public Driver.State state() {
        return state;
    }

    @Override
    public Driver.State tick() {
        if (state != Driver.State.RUNNING) {
            return state;
        }
        var body = rig.entity;
        if (Snapshots.flying(body)) {
            rig.keys.release(Key.JUMP);
            rig.keys.press(Key.SNEAK);
            return state;
        }
        rig.keys.release(Key.SNEAK);
        rig.ledger.alighted(body.blockPosition());
        PathLog.info("{} 走路开始时在飞,先落到地上 {} {}", rig.who, PathLog.pos(body.blockPosition()), PathLog.body(body));
        state = Driver.State.ARRIVED;
        return state;
    }

    @Override
    public Halt halt() {
        return null;
    }

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

    /** 垂直降下去是计划内的。 */
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
        rig.keys.release(Key.SNEAK);
        if (state == Driver.State.RUNNING) {
            state = Driver.State.HALTED;
        }
    }

    @Override
    public Blockage nearestRefusal() {
        return null;
    }
}
