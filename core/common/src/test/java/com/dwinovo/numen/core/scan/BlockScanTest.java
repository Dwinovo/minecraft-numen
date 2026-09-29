package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 看到的团写进区域:每一团一部分、{@code g} 编号在区域里续、每格附带看到的方块与那一刻;存盘读回(重启)之后一模一样,
 * {@code ores/g2} 仍指同一团,挖之前的复核认的还是当时那种方块。
 */
class BlockScanTest {

    private static boolean booted;
    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

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
        assumeTrue(booted, "Minecraft 引导不可用,跳过写进区域的钉桩");
    }

    /** 两团铁矿:近的一格,远的两格。 */
    private static BlockScan.Found twoGroups(long tick) {
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(3, 64, 0), Blocks.IRON_ORE.defaultBlockState(), Verdict.allow());
        groups.add(new BlockPos(20, 60, 0), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Verdict.allow());
        groups.add(new BlockPos(21, 60, 0), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Verdict.allow());
        return new BlockScan.Found(groups.grouped(CENTER), null, CENTER, tick);
    }

    @Test
    void eachGroupBecomesAPartNumberedOnFromTheArea() {
        Area ores = Area.of(Level.OVERWORLD, Area.Kind.BOX, Cells.box(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));
        BlockScan.Added first = twoGroups(100L).into(ores);
        assertEquals(List.of("g1", "g2"), first.ids());
        assertEquals(List.of("b1", "g1", "g2"), first.area().parts().stream().map(Area.Part::id).toList());
        Cells near = first.area().part("g1").cells();
        assertEquals(1, near.size());
        assertEquals(Blocks.IRON_ORE, near.seenAt(new BlockPos(3, 64, 0)).state().getBlock());
        assertEquals(100L, near.seenAt(new BlockPos(3, 64, 0)).tick());

        BlockScan.Added again = twoGroups(200L).into(first.area());
        assertEquals(List.of("g3", "g4"), again.ids(), "再看一次接着往上数,旧的编号不会指到新的团");
    }

    @Test
    void anAreaKeptByAScanReadsBackAfterARestart() {
        Area ores = twoGroups(100L).into(Area.empty(Level.OVERWORLD)).area();
        Area back = Area.load(ores.save(), BuiltInRegistries.BLOCK.asLookup());
        assertEquals(ores, back);
        Area g2 = AreaRef.parse("ores/g2").resolve(Map.of("ores", back));
        assertEquals(2, g2.cells().size());
        Cells.Seen seen = g2.cells().seenAt(new BlockPos(20, 60, 0));
        assertEquals(Blocks.DEEPSLATE_IRON_ORE, seen.state().getBlock());
        assertTrue(seen.holds(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()));
        assertFalse(seen.holds(Blocks.STONE.defaultBlockState()), "换了方块就不是当时那一格");
    }

    @Test
    void stillTheSameBlockIgnoresItsState() {
        Cells.Seen log = new Cells.Seen(Blocks.OAK_LOG.defaultBlockState(), 1L);
        assertTrue(log.holds(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)),
                "原木换了朝向还是那根原木");
        assertFalse(log.holds(Blocks.AIR.defaultBlockState()));
    }
}
