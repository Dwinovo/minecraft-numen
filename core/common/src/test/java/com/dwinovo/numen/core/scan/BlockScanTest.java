package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.area.Cells;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 看到的方块之后还是不是它:挖一个点名的 Block 之前复核认的是方块种类,不比状态。 */
class BlockScanTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过复核的钉桩");
    }

    @Test
    void stillTheSameBlockIgnoresItsState() {
        Cells.Seen log = new Cells.Seen(Blocks.OAK_LOG.defaultBlockState(), 1L);
        assertTrue(log.holds(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)),
                "原木换了朝向还是那根原木");
        assertFalse(log.holds(Blocks.AIR.defaultBlockState()));
    }
}
