package com.dwinovo.numen.api.gear;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * 一处能把东西穿戴在身上的来源:原版四件甲是一处,饰品栏(Curios)是一处。经
 * {@link com.dwinovo.numen.api.NumenApi#registerGear} 登记。
 *
 * <p>{@code equip_item} 的穿、脱、自动选位,以及每轮挂给模型的 {@code <worn>},都只经这里——原版和模组
 * 走同一扇门,没有"原版先定、模组兜底"。
 *
 * <p>来源只陈述游戏规则(这件能不能放进这格、这格里的能不能摘),不做许可裁决:穿戴不改世界、不伤实体。
 * 只在服务端主线程调用。
 */
public interface GearSource {

    /**
     * 这具身体此刻有的位置,顺序固定——自动选位按这个顺序挑。句柄只在本次调用内有效,别存。
     * 身上没有这处的位置就给空表。
     */
    List<GearSlot> slots(NumenPlayer body);

    /**
     * 按这处来源的规矩,这件东西该戴在哪类位置({@link GearSlot#name} 同名),不管她身上有没有这类位置。
     *
     * <p>空集 = 不归这处管。它分开两件事:"该戴却戴不上"(如实失败,说出缺哪类位置)与
     * "根本不是穿戴物"(交给手)。
     */
    Set<String> kindsOf(NumenPlayer body, ItemStack stack);
}
