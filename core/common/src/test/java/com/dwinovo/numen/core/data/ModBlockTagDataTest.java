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
 * safe_block_entity_data 默认成员的回归钉,打在唯一真源({@link ModBlockTagData})上:
 * 这个标签就是"图纸可以印出哪些方块实体数据"的授权,多一个容器就是凭空造物,
 * 所以默认只能是牌子和旗帜。
 *
 * <p>纯 JVM:录制假 Appender,只经手 TagKey,不触碰注册表、不需要引导。
 */
class ModBlockTagDataTest {

    @Test
    void safeBlockEntityDataDefaultsToSignsAndBanners() {
        Map<TagKey<Block>, List<TagKey<Block>>> tagRefs = new HashMap<>();
        Map<TagKey<Block>, List<Block>> directAdds = new HashMap<>();
        ModBlockTagData.addBlockTags(key -> ModItemTagData.appender(
                b -> directAdds.computeIfAbsent(key, k -> new ArrayList<>()).add(b),
                t -> tagRefs.computeIfAbsent(key, k -> new ArrayList<>()).add(t)));

        assertEquals(List.of(BlockTags.ALL_SIGNS, BlockTags.BANNERS),
                tagRefs.get(InitTag.SAFE_BLOCK_ENTITY_DATA));
        // 全部走原版标签引用:成员随版本自动跟上,不逐个列
        assertNull(directAdds.get(InitTag.SAFE_BLOCK_ENTITY_DATA));
        // 只剩这一个标签:哪些方块能不能挖是权限层规则表的事,不是标签的事
        assertEquals(1, tagRefs.size());
    }
}
