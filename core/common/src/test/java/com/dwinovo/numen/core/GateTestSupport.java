package com.dwinovo.numen.core;

import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.TerritoryClaims;

/**
 * 无头测试用的裁决快照:出厂规则、ask 模式、没有任何玩家放置记录——和一个新世界里
 * 刚召出来的同伴面对的一模一样。成本测试要的是"自然方块按自然价",就用它。
 */
public final class GateTestSupport {

    private GateTestSupport() {}

    public static Gate open() {
        return new Gate(null, Mode.ASK, RuleSet.factory(), new PlacedBlocks(), TerritoryClaims.NONE);
    }
}
