package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落在哪种方块上、摔多高掉几点血,逐项对照原版 1.21.1:{@code Block.fallOn} 及其覆写({@code PointedDripstoneBlock}、
 * {@code HayBlock}、{@code HoneyBlock}、{@code SlimeBlock}、{@code BedBlock}、{@code PowderSnowBlock})把落差与倍率交给
 * {@code LivingEntity.calculateFallDamage}:{@code ceil((落差 - 安全高度) * 倍率 * fall_damage_multiplier)},前两步是 float。
 * 下面每个期望值都是按这条式子手算的原版结果;身体是原版玩家(安全高度 3、属性倍率 1)。
 */
class FallDamageTest {

    private static BodySnapshot body;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        body = Fixtures.body();
    }

    private static BlockState stalagmite() {
        return Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);
    }

    @Test
    void hardGroundHurtsPastTheSafeDistance() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertEquals(0, body.fallDamage(3, stone));
        assertEquals(1, body.fallDamage(4, stone));
        assertEquals(7, body.fallDamage(10, stone));
        assertEquals(15, body.fallDamage(17.5, stone), "向上取整");
    }

    @Test
    void anUpwardDripstoneTipAddsTwoBlocksAndDoublesIt() {
        // PointedDripstoneBlock.fallOn:causeFallDamage(落差 + 2, 2)
        assertEquals(0, body.fallDamage(1, stalagmite()), "落一格还不疼");
        assertEquals(1, body.fallDamage(1.3125, stalagmite()), "从整块顶上走下一级落在锥尖上:ceil(0.3125 * 2)");
        assertEquals(3, body.fallDamage(2.3125, stalagmite()));
        assertEquals(4, body.fallDamage(3, stalagmite()), "石头上不疼的三格,落在锥尖上掉 4 点");
        assertEquals(16, body.fallDamage(9, stalagmite()));
    }

    @Test
    void onlyAnUpwardTipIsSharp() {
        BlockState frustum = stalagmite().setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.FRUSTUM);
        BlockState downward = stalagmite().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN);
        assertEquals(1, body.fallDamage(4, frustum), "锥身按硬地");
        assertEquals(1, body.fallDamage(4, downward), "朝下的锥尖按硬地");
        assertEquals(0, body.fallDamage(3, frustum));
    }

    @Test
    void hayAndHoneyCutItToAFifth() {
        // HayBlock / HoneyBlock.fallOn:causeFallDamage(落差, 0.2)
        for (BlockState soft : List.of(Blocks.HAY_BLOCK.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState())) {
            assertEquals(0, body.fallDamage(3, soft));
            assertEquals(1, body.fallDamage(4, soft), "ceil(0.2)");
            assertEquals(1, body.fallDamage(8, soft), "5 * 0.2F 按 float 正好是 1,不进位成 2");
            assertEquals(4, body.fallDamage(23, soft));
            assertEquals(14, body.fallDamage(72, soft));
        }
    }

    @Test
    void aBedHalvesTheDrop() {
        // BedBlock.fallOn:按落差的一半交给默认的 fallOn
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT);
        assertEquals(0, body.fallDamage(6, bed));
        assertEquals(1, body.fallDamage(7, bed), "ceil(3.5 - 3)");
        assertEquals(7, body.fallDamage(20, bed));
    }

    @Test
    void slimeAndPowderSnowDoNotHurt() {
        // SlimeBlock.fallOn 不潜行时倍率 0;PowderSnowBlock.fallOn 不调 causeFallDamage
        assertEquals(0, body.fallDamage(50, Blocks.SLIME_BLOCK.defaultBlockState()));
        assertEquals(0, body.fallDamage(50, Blocks.POWDER_SNOW.defaultBlockState()));
    }

    @Test
    void creativeNeverHurtsAndTheAttributesScaleIt() {
        BodySnapshot creative = Fixtures.body(Vanilla.CREATIVE, GameType.CREATIVE, 20, List.of());
        assertEquals(0, creative.fallDamage(30, stalagmite()));
        BodySnapshot light = new BodySnapshot(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, 5, 0.5, 20, 0, List.of(),
                BodySnapshot.Mining.VANILLA, Breath.VANILLA);
        assertEquals(3, light.fallDamage(10, Blocks.STONE.defaultBlockState()), "ceil((10 - 5) * 0.5)");
        assertEquals(2, light.fallDamage(5, stalagmite()), "ceil((5 + 2 - 5) * 2 * 0.5)");
    }

    @Test
    void theBodyBearsWhatLeavesItSixHealth() {
        assertTrue(body.bears(0));
        assertTrue(body.bears(14), "20 点血摔掉 14 还剩 6");
        assertFalse(body.bears(15));
        BodySnapshot weak = Fixtures.body(5);
        assertTrue(weak.bears(0), "不疼的总受得起");
        assertFalse(weak.bears(1));
    }
}
