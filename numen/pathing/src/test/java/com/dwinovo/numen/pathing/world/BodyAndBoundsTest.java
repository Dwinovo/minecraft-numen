package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 眼睛的位置与世界边界。 */
class BodyAndBoundsTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void theEyeIsAtTheColumnCentreAtEyeHeight() {
        assertEquals(64 + 1.62, SURVIVAL.eye(0, 64, 0).y, 1e-6);
        assertEquals(0.5, SURVIVAL.eye(0, 64, 0).x, 1e-9);
    }

    @Test
    void theBodyStaysInsideTheWorldBorder() {
        WorldBorder border = new WorldBorder();
        border.setSize(10);   // 以 0,0 为中心,x、z 在 [-5, 5)
        assertTrue(Bounds.holdsBody(border, SURVIVAL, 4, 0));
        assertTrue(Bounds.holdsBody(border, SURVIVAL, -5, 0));
        assertFalse(Bounds.holdsBody(border, SURVIVAL, 5, 0));
    }

    @Test
    void cellsOutsideTheBorderOrTheBuildHeightCannotBeEdited() {
        WorldBorder border = new WorldBorder();
        border.setSize(10);
        TestWorld heights = new TestWorld();   // 建筑高度 -64 到 319
        assertTrue(Bounds.allowsEdit(border, heights, new BlockPos(4, 64, 0)));
        assertFalse(Bounds.allowsEdit(border, heights, new BlockPos(5, 64, 0)));
        assertFalse(Bounds.allowsEdit(border, heights, new BlockPos(0, 320, 0)));
        assertFalse(Bounds.allowsEdit(border, heights, new BlockPos(0, -65, 0)));
    }
}
