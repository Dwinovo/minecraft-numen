package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.gametest.mixin.inject.GameTestHelperBoundsInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/** 一条用例的场地占的范围(绝对坐标),即原版的 {@code GameTestHelper.getBounds}——这一代它是私有的。 */
public final class TestBounds {

    private TestBounds() {}

    public static AABB of(GameTestHelper helper) {
        return ((GameTestHelperBoundsInvoker) helper).numen$bounds();
    }

    /** 场地里的每一格(可变坐标,要留就 {@code immutable()})。 */
    public static Iterable<BlockPos> cells(GameTestHelper helper) {
        AABB box = of(helper);
        return BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }
}
