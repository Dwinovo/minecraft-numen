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
    }
}
