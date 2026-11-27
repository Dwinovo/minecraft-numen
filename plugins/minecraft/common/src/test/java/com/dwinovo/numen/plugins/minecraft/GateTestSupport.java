package com.dwinovo.numen.plugins.minecraft;

import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.permission.Settings;

/**
 * 无头测试用的裁决快照:出厂设置、没有谁回答方块归属——和一个新世界里刚召出来的同伴面对的一模一样。
 * 成本测试要的是"没主人的方块按原价",就用它。
 */
public final class GateTestSupport {

    private GateTestSupport() {}

    public static Gate open() {
        return new Gate(Actor.NOBODY, Settings.FACTORY, Owners.Blocks.NONE, java.util.List.of());
    }
}
