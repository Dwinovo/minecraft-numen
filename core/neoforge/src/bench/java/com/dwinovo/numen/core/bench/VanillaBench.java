package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Bench;
import com.dwinovo.numen.bench.BenchSuite;

import java.util.Optional;

/** 原版的评测场景:只用原版方块与物品,不挂任何联动。 */
public final class VanillaBench {

    private VanillaBench() {}

    @BenchSuite
    public static Optional<Bench.Run> scenarios() {
        return Bench.suite("vanilla",
                "Vanilla Minecraft: gathering, crafting chains, building, fighting and farming, no other mods.",
                // 回归集在前,能力集在后
                suite -> suite.add(MineIron::new)
                        .add(CraftStonePickaxe::new)
                        .add(DeepDiamond::new)
                        .add(WalledChest::new)
                        .add(GuardOwner::new)
                        .add(BuildWall::new)
                        .add(IronPickaxeChain::new)
                        .add(HarvestAndBread::new));
    }
}
