package com.dwinovo.numen;

import net.minecraft.world.level.Level;
import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.FactoryRules;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Layers;
import com.dwinovo.numen.api.permission.Hits;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.api.permission.Structures;
import com.dwinovo.numen.permission.RuleSet;

/**
 * 无头测试用的裁决快照:出厂规则、ask 模式、没有谁回答方块归属——和一个新世界里
 * 刚召出来的同伴面对的一模一样。成本测试要的是"没主人的方块按原价",就用它。
 */
public final class GateTestSupport {

    private GateTestSupport() {}

    public static Gate open() {
        return new Gate(Actor.NOBODY, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), Structures.NONE, Owners.Blocks.NONE, java.util.List.of(), Hits.NONE);
    }
}
