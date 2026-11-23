package com.dwinovo.numen.api;

import com.dwinovo.numen.api.describe.Describer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Numen API 自己出的题,都列在这里。回答者经 {@link NumenApi#answer} 登记;原版的回答由 Numen 从同一扇门登记,和插件一个写法。
 */
public final class Questions {

    private Questions() {}

    /**
     * 这一格方块是什么:她细看一格时,每个回答者说它认得的那部分(告示牌上的字、里面存着什么、你的模组的机器在干什么),各归在自己的
     * 名字下,拼进这次"看"的结果。见 {@link Describer}。
     */
    public static final Question<Describer<BlockPos>> BLOCK = Question.of("block");

    /**
     * 这只实体是什么:她细看一只实体时,每个回答者说它认得的那部分(村民的职业、身上穿的拿的、驯没驯服),各归在自己的名字下。
     * 见 {@link Describer}。
     */
    public static final Question<Describer<Entity>> ENTITY = Question.of("entity");
}
