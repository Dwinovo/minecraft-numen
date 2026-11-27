package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/** 查物品说明的答复:物品事实(只有原版的函数给),以及写下的说明。 */
@Doc("What is known about an item.")
public record InfoPage(@Doc("The game's own facts about the item; only the game's own lookup gives them.")
                       Optional<ItemFacts> facts,
                       @Doc("Descriptions: what the item is for, as the modpack or the mod wrote them.")
                       List<String> notes) {}
