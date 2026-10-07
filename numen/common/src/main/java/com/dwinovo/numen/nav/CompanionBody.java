package com.dwinovo.numen.nav;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Body;

import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路的身体端口({@link Body})在 Numen 一侧的实现:把一个同伴接进去。寻路只经 {@code entity}、{@code controls}、{@code look}、
 * {@code snapshot} 几个口用身体,不按身体对象存状态,所以这里是个无状态的壳,每开一趟路包一次就行。
 */
record CompanionBody(NumenPlayer player) implements Body {

    @Override
    public ServerPlayer entity() {
        return player;
    }

    @Override
    public Controls controls() {
        return player.controls();
    }

    @Override
    public Look look() {
        return player.look();
    }
}
