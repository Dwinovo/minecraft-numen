package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.data.ModItemTagData.TagAppenderProvider;
import com.dwinovo.numen.core.init.InitTag;
import net.minecraft.world.level.block.Block;

/**
 * Single source of truth for every block tag numen-core emits — mirrors
 * {@link ModItemTagData} but for the block registry. Both loaders' block-tag
 * providers forward here, so the content stays in {@code common/}.
 *
 * <h2>Adding a new tag</h2>
 * <ol>
 *   <li>Declare the {@code TagKey<Block>} in {@link InitTag}.</li>
 *   <li>Add a {@code tags.tag(InitTag.X).add(Blocks.Y)} block in {@link #addBlockTags}.</li>
 *   <li>Re-run {@code ./gradlew :fabric:runDatagen :neoforge:runData}.</li>
 * </ol>
 */
public final class ModBlockTagData {

    private ModBlockTagData() {}

    /**
     * do_not_break 标签默认为空:寻路的硬禁挖清单由 NavSettings
     * .blocksToDisallowBreaking(默认空)在运行期管理,与 Baritone
     * MovementHelper 的 blocksToDisallowBreaking 同义。功能方块
     * (工作台/熔炉/箱子/陷阱箱)走 NavSettings.blocksToAvoidBreaking
     * 软惩罚(挖掘成本 ×10,无路可走仍会破坏),不在此标签内。数据包
     * 可自由往此标签追加要硬禁挖的方块(任何开关都不破坏)。
     */
    public static void addBlockTags(TagAppenderProvider<Block> tags) {
        tags.tag(InitTag.DO_NOT_BREAK);

        // 图纸可以把方块实体数据带进世界的那些方块。默认只有牌子和旗帜:牌子上的字是
        // 纯文本(玩家自己写也是白写的),旗帜的花纹是设计的一部分而料按带花纹的那面
        // 旗帜收。两者都不产出任何凭空的东西。
        //
        // 引用原版标签而不是逐个列成员:成员会随版本变(1.20 加了悬挂告示牌),而
        // "所有牌子"这个意思不会变。
        //
        // 往这个标签里加容器意味着图纸可以印出里面的东西——那是数据包作者的决定,
        // 但得是知情的决定。
        tags.tag(InitTag.SAFE_BLOCK_ENTITY_DATA)
                .addTag(net.minecraft.tags.BlockTags.ALL_SIGNS)
                .addTag(net.minecraft.tags.BlockTags.BANNERS);
    }
}
