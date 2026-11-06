package com.dwinovo.numen.api.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 身体为走路做的一个动作,不改世界,但改了身体:把东西换到了手上、创造模式凭空取了料。它们都记进结局,由宿主
 * 如实报出去——身体动了,调用方要知道。
 */
public sealed interface BodyAction {

    /**
     * 把 {@code stack}(一件,组件原样)拿到了主手:原来在背包的第 {@code from} 格,现在在快捷栏第 {@code to} 格;两格相同就是只切换了选中的格。
     * 带着整叠的样子而不只是物品:同一种物品有几叠不同的时候,回执要说拿的是哪一叠。
     */
    record Held(ItemStack stack, int from, int to) implements BodyAction {

        public Item item() {
            return stack.getItem();
        }
    }

    /** 创造模式凭空取了一叠 {@code item},放在快捷栏第 {@code to} 格。 */
    record Conjured(Item item, int to) implements BodyAction {}
}
