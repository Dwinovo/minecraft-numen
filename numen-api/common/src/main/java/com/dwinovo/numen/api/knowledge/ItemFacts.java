package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/**
 * 一样物品原版本身给得出的事实({@code numen.inv.info} 的 {@code facts}):服务端读注册表与默认组件就有,不依赖任何模组。
 */
@Doc("What the game itself says about an item.")
public record ItemFacts(@Doc("The item id, minecraft:iron_ingot.") String item,
                        @Doc("How many fit in one stack.") int maxStack,
                        @Doc("Tools, weapons and armor: how many uses it has before it breaks.")
                        Optional<Integer> durability,
                        @Doc("Food and drink: what eating it gives.") Optional<Food> food,
                        @Doc("The block it places, minecraft:oak_log; absent when it places none.")
                        Optional<String> places,
                        @Doc("The item tags it is in, minecraft:planks. A recipe's \"any\" ingredient is a tag.")
                        List<String> tags) {

    /** 吃了得到什么。 */
    public record Food(@Doc("Hunger points restored.") int nutrition,
                       @Doc("Saturation restored.") double saturation,
                       @Doc("It can be eaten when not hungry.") boolean always) {}
}
