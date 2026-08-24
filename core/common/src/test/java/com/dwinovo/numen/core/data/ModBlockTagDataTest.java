package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.init.InitTag;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * do_not_break 默认成员的回归钉,打在唯一真源({@link ModBlockTagData})上。
 * 成员的入选判据见那边的注释;这里只保证"设施类默认受硬保护"不被悄悄改掉。
 * 标签→INF 的机制另由 ProtectionPinsTest 钉。
 *
 * <p>录制假 Appender,只经手 TagKey。这一代(1.20.2)创建 TagKey 会连带初始化
 * 注册表类,而注册表类要求先引导——所以照别的钉桩一样先引导再跑。
 */
@Tag("mc")
class ModBlockTagDataTest {

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

    @Test
    void doNotBreakDefaultsToFacilityTags() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过标签钉桩");
        Map<TagKey<Block>, List<TagKey<Block>>> tagRefs = new HashMap<>();
        Map<TagKey<Block>, List<Block>> directAdds = new HashMap<>();
        ModBlockTagData.addBlockTags(key -> ModItemTagData.appender(
                b -> directAdds.computeIfAbsent(key, k -> new ArrayList<>()).add(b),
                t -> tagRefs.computeIfAbsent(key, k -> new ArrayList<>()).add(t)));

        assertEquals(
                List.of(BlockTags.BEDS, BlockTags.DOORS, BlockTags.TRAPDOORS, BlockTags.FENCE_GATES),
                tagRefs.get(InitTag.DO_NOT_BREAK));
        // 全部走原版标签引用:成员随版本自动跟上,不逐个列
        assertNull(directAdds.get(InitTag.DO_NOT_BREAK));
    }
}
