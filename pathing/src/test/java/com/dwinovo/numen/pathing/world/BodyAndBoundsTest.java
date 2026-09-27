package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.border.WorldBorder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 身体的起跳高度与世界边界。 */
class BodyAndBoundsTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void aVanillaJumpClearsOneBlockButNotAFence() {
        double jump = SURVIVAL.jumpHeight(1.0);
        assertTrue(jump > 1.25 && jump < 1.26, "原版玩家起跳约 1.252,实为 " + jump);
        assertTrue(SURVIVAL.jumpHeight(0.5) < 0.5, "蜂蜜块上跳不过半格");
    }

    @Test
    void standingAndCrouchingMustShareOneWidth() {
        assertThrows(IllegalArgumentException.class, () -> new BodyStats(EntityDimensions.scalable(0.6F, 1.8F),
                EntityDimensions.scalable(0.5F, 1.5F), 0.6, 0.42, 0.08, 4.5, false, false));
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
