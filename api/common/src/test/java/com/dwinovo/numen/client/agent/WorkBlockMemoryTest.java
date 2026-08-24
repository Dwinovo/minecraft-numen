package com.dwinovo.numen.client.agent;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台面类型的归一口径:模组原地替换原版设施时惯用「自家命名空间 + 包一层
 * 原版路径」的注册名,记录与自愈对账必须都认得出——两边同一个函数。
 */
class WorkBlockMemoryTest {

    @TempDir
    Path tmp;

    @Test
    void stationTypeNormalizesModWrappedIds() {
        assertEquals("crafting_table", WorkBlockMemory.stationType("minecraft:crafting_table"));
        assertEquals("crafting_table",
                WorkBlockMemory.stationType("visualworkbench:minecraft/crafting_table"));
        assertEquals("crafting_table", WorkBlockMemory.stationType("crafting_table"));
    }

    @Test
    void trackednessSeesThroughTheWrapping() {
        assertTrue(WorkBlockMemory.isTracked("somemod:minecraft/furnace"));
        assertFalse(WorkBlockMemory.isTracked("somemod:minecraft/oak_planks"));
    }

    @Test
    void recordStoresTheNormalizedType() {
        CompanionHome.init(tmp);
        WorkBlockMemory mem = WorkBlockMemory.forEntity(UUID.randomUUID());
        mem.record("visualworkbench:minecraft/crafting_table", new BlockPos(1, 2, 3));
        assertTrue(mem.formatXml(null).contains("type=\"crafting_table\""));
    }
}
