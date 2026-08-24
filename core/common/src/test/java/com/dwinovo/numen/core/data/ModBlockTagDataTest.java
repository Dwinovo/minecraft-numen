package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.init.InitTag;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * do_not_break 默认成员的回归钉,打在唯一真源({@link ModBlockTagData})上。
 * 成员的入选判据见那边的注释;这里只保证"设施类默认受硬保护"不被悄悄改掉。
 * 标签→INF 的机制另由 ProtectionPinsTest 钉。
 *
 * <p>纯 JVM:录制假 Appender,只经手 TagKey,不触碰注册表、不需要引导。
 */
class ModBlockTagDataTest {

    @Test
    void doNotBreakDefaultsToFacilityTags() {
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
