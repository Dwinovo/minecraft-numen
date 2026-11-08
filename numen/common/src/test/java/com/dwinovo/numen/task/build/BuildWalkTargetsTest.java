package com.dwinovo.numen.task.build;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 一站放完、还有够不着的格:走去下一站时交给寻路的是最低一层里够不着的每一格,寻路去离她最近、够得着其中任一格的站位——不是只交
 * "最远那一格"。只交最远那一格时,她每一站都横穿工地去站最远处,铺一片地基在工地两头来回跑(6d31938c2 修的就是它)。
 */
@Tag("mc")
class BuildWalkTargetsTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void everyCellOfTheLowestLayerGoesToThePathfinderNearAndFarAlike() {
        BlockPos near = new BlockPos(1, 64, 0);
        BlockPos middle = new BlockPos(7, 64, 7);
        BlockPos far = new BlockPos(14, 64, 14);
        BlockPos above = new BlockPos(1, 65, 0);
        assertEquals(List.of(near, middle, far), BuildCompanionTask.walkTargets(List.of(near, middle, far, above)));
    }

    @Test
    void aHigherLayerWaitsUntilTheLowerOneIsReached() {
        BlockPos low = new BlockPos(14, 64, 14);
        assertEquals(List.of(low), BuildCompanionTask.walkTargets(List.of(low, new BlockPos(0, 65, 0),
                new BlockPos(0, 66, 0))));
    }
}
