package com.dwinovo.numen.core;

import com.dwinovo.numen.core.init.InitTag;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 给无头测试绑上垫路料标签。
 *
 * <p>{@code numen:scaffolds} 的内容来自数据包,而 {@code Bootstrap.bootStrap()} 只建注册表、
 * 不加载数据包。不绑的话标签是空的,于是"她一件垫路料都没有"——寻路里所有垫柱/搭桥判成
 * {@code COST_INF},成本测试和选料测试会以一个看不出原因的方式全挂。
 *
 * <p>用的是原版自己的 {@link MappedRegistry#bindTag}(数据包加载走的也是这条路),所以生产
 * 代码不必为测试开任何口子。绑的内容是<b>测试自己声明的</b>一小撮,不去读 datagen 产物——
 * 那些文件不进版本库,测试不该依赖"你跑过 datagen"。
 */
public final class ScaffoldTagTestSupport {

    /** 够用就行:成本与优先级测试只关心"有料"和"泥土排在圆石前面"。 */
    private static final List<Item> MATERIALS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.STONE, Items.NETHERRACK,
            Items.COBBLED_DEEPSLATE, Items.COARSE_DIRT);

    private ScaffoldTagTestSupport() {}

    /** 幂等:每个测试类的 {@code @BeforeAll} 都可以调。 */
    @SuppressWarnings("unchecked")
    public static void bind() {
        ((MappedRegistry<Item>) BuiltInRegistries.ITEM).bindTag(   // 1.21.2+ 按标签逐条绑
                InitTag.SCAFFOLDS,
                MATERIALS.stream().map(BuiltInRegistries.ITEM::wrapAsHolder).toList());
    }
}
