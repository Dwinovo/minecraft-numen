package com.dwinovo.numen.scenario;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.bench.Bench;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;

import java.util.Collection;
import java.util.List;

/** 原版的评测场景(套件 vanilla):只用原版方块与物品,不挂任何联动。 */
public final class VanillaBench implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(VanillaBench.class);
    }

    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
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
