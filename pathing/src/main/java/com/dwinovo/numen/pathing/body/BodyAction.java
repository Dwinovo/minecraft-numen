package com.dwinovo.numen.pathing.body;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * 身体为走路做的一个动作,不改世界,但改了身体:下了载具、把东西换到了手上、创造模式凭空取了料。它们都记进结局,由宿主
 * 如实报出去——身体动了,调用方要知道。
 */
public sealed interface BodyAction {

    /** 从 {@code vehicle} 上下来了。 */
    record Dismounted(EntityType<?> vehicle) implements BodyAction {}

    /**
     * 把 {@code item} 拿到了主手:原来在背包的第 {@code from} 格,现在在快捷栏第 {@code to} 格;两格相同就是只切换了选中的格。
     */
    record Held(Item item, int from, int to) implements BodyAction {}

    /** 创造模式凭空取了一叠 {@code item},放在快捷栏第 {@code to} 格。 */
    record Conjured(Item item, int to) implements BodyAction {}
}
