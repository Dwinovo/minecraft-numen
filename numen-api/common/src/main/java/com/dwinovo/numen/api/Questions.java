package com.dwinovo.numen.api;

import com.dwinovo.numen.api.describe.Describer;
import com.dwinovo.numen.api.entity.ClickActions;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.permission.RuleWord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

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

    /**
     * 这一叠物品和一件新物品有什么不一样:每个回答者说它认得的那部分(附魔、里面装着什么、你的模组存在物品上的数据),各归在自己的
     * 名字下。和新物品没有区别的一叠没人说话。背包清单里的每一种、回执里写到的每一叠都问一圈
     * ({@link com.dwinovo.numen.api.entity.StackLabel}),比细看一格方块问得勤。回答者只读这一叠,不改它。见 {@link Describer}。
     */
    public static final Question<Describer<ItemStack>> ITEM = Question.of("item");

    /**
     * 手里拿着这一叠、右键按在方块的这一面上,会对世界做哪几件事:每个回答者说它认得的那种物品(方块物品放下一格、桶倒出液体、
     * 你的模组的放置类物品)。说出来的动作在按下去之前交权限层裁决,和"用这一格"那一问合成一张单。见 {@link ClickActions}。
     */
    public static final Question<ClickActions> CLICK = Question.of("click");

    /**
     * 规则里能写的词:回答者的名字就是那个词({@code contents}、{@code hostile}),回答者说这个词在一个动作的对象上成不成立。
     * 主人的规则、出厂的规则写的词,除了 Numen API 自带的那几个({@link Groups}:归属、{@code near}、{@code block_entity}、
     * {@code named}),都是这里登记的;没人登记的词写进规则是解析错误。自带的名字登记不了。见 {@link RuleWord}。
     */
    public static final Question<RuleWord> RULE_WORD = Question.of("rule_word", Groups.names());
}
