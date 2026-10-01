package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Bench;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.Collection;

/** 原版的评测场景:只用原版方块与物品,不挂任何联动。 */
@GameTestHolder(Bench.NAMESPACE)
public final class VanillaBench {

    private VanillaBench() {}

    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
        return Bench.suite("vanilla", "Vanilla Minecraft: gathering and the basics, no other mods.",
                suite -> suite.add(MineIron::new)
                        .add(MineIron::byScript)
                        .add(DeepDiamond::new)
                        .add(OreBehindHouse::denied)
                        .add(OreBehindHouse::allowedOnce)
                        .add(WalledChest::new)
                        .add(BuildHut::new)
                        .add(CraftPickaxe::new)
                        .add(GuardOwner::new)
                        .add(PickUpDrops::new)
                        .add(FarPillar::new));
    }
}
