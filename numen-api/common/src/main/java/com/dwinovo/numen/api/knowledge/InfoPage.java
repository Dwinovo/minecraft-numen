package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/** 一个来源对 {@code info} 的答复:它知道的物品事实(只有原版的来源给),以及它写下的说明。 */
@Doc("What one source says about an item.")
public record InfoPage(@Doc("The game's own facts about the item; only the vanilla source gives them.")
                       Optional<ItemFacts> facts,
                       @Doc("Descriptions: what the item is for, as the modpack or the mod wrote them.")
                       List<String> notes) {}
