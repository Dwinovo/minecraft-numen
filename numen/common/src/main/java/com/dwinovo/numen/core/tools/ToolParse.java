package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.init.InitTag;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具入口共享的参数解析件:方块 id 与标签展开成方块集。物品 id 由 SDK 的值转换读。
 */
public final class ToolParse {

    private ToolParse() {}

    /**
     * 宽松的方块 id 集:解析失败/未知/air 的条目跳过,保留输入顺序
     * (消息里的"第一个目标"标签依赖顺序)。
     *
     * <p><b>{@code #} 开头的条目是标签</b>,原样展开成它当下的全部成员——{@code #minecraft:beds}
     * 是"床这一类"而不是某一种颜色的床。这是原版自己的语法(标签文件的 {@code values} 里就用
     * 它引用别的标签),不是我们发明的写法,所以模型写出来的和它在数据包里见过的一致。
     *
     * <p>标签内容来自数据包,世界加载后才有,所以这里<b>每次调用现查</b>——{@code /reload}
     * 改了标签下一次就生效。
     */
    public static Set<Block> parseBlocks(List<String> ids) {
        Set<Block> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String raw : ids) {
            if (raw == null) continue;
            TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
            if (tag != null) {
                for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                    Block b = holder.value();
                    if (b != Blocks.AIR) out.add(b);
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }
}
