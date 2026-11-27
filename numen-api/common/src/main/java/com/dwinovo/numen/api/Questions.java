package com.dwinovo.numen.api;

import com.dwinovo.numen.api.describe.Describer;
import com.dwinovo.numen.api.entity.ClickActions;
import com.dwinovo.numen.api.permission.BlockOwner;
import com.dwinovo.numen.api.permission.EntityOwner;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.api.permission.Answerer;
import com.dwinovo.numen.api.permission.Ownership;
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
     * 这个动作、这个对象,许不许:每个回答者说它懂的那部分——不许、要问、许、不表态——权限层把一圈回答合成一个结果
     * (不许 > 主人的名单 > 要问 > 许 > 主人给这种动作定的档)。回答者只说自己懂的,互相不知道对方。内置的回答者只有"归属"
     * ({@link Ownership}):别人的不许,主人的要问。见 {@link Answerer}。
     */
    public static final Question<Answerer> PERMIT = Question.of("permit");

    static {
        PERMIT.register(Ownership.NAME, new Ownership());
    }

    /**
     * 这只实体是谁的:每个回答者说它认得的那一类(原版的驯服记录、你的模组自己记的主人),是某个人的就交回那个人。没人回答就是没主人的。
     * 权限层的归属、她每轮看四周、感知函数里的 owner 问的都是这一圈({@link Owners})。见 {@link EntityOwner}。
     */
    public static final Question<EntityOwner> ENTITY_OWNER = Question.of("entity_owner");

    /**
     * 这一格方块是谁的:知道哪些格子有主人的一方(领地模组的插件)回答,先在主线程上交回她身边一片的查表,之后逐格问那份查表。
     * 没人回答就是没主人的;原版里方块没有主人,所以没有原版的回答者。见 {@link BlockOwner}。
     */
    public static final Question<BlockOwner> BLOCK_OWNER = Question.of("block_owner");
}
