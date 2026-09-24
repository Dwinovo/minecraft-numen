package com.dwinovo.numen.api.gear;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 身上的一个穿戴位置,由 {@link GearSource#slots} 给出。判据与玩家在界面里拖动那一格是同一条路径。
 */
public interface GearSlot {

    /**
     * 模型写在 {@code slot} 参数里、在 {@code <worn>} 里看到的名字。同类多格同名。原版用裸名
     * ({@code head}、{@code chest}…),其它来源带自己的 mod id 前缀({@code curios:ring})。
     */
    String name();

    /** 这格里现在戴着的;空栈 = 空位。 */
    ItemStack worn();

    /** 能不能把这一件放进来:空 = 能;否则是给模型看的真实原因。 */
    Optional<String> refuseWear(ItemStack one);

    /** 能不能把现在这件摘下来:空 = 能(空位也是空);否则是给模型看的真实原因。 */
    Optional<String> refuseRemove();

    /**
     * 放入 {@code in}({@link ItemStack#EMPTY} = 摘空),返回原来那件。不碰背包——东西从哪来、摘下来去哪,
     * 由调用方管。调用方先问过 {@link #refuseWear} / {@link #refuseRemove}。
     */
    ItemStack swap(ItemStack in);
}
