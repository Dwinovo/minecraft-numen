package com.dwinovo.numen.core.scaffold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Defines which inventory blocks may be sacrificed for temporary navigation. */
public final class ScaffoldMaterialPolicy {
    public enum CostTier {
        CHEAPEST,
        COMMON,
        EXPENSIVE
    }

    private static final Set<String> CHEAPEST = Set.of(
        "minecraft:blackstone",
        "minecraft:calcite",
        "minecraft:coarse_dirt",
        "minecraft:cobbled_deepslate",
        "minecraft:cobblestone",
        "minecraft:deepslate",
        "minecraft:dirt",
        "minecraft:end_stone",
        "minecraft:grass_block",
        "minecraft:moss_block",
        "minecraft:mud",
        "minecraft:mycelium",
        "minecraft:netherrack",
        "minecraft:podzol",
        "minecraft:rooted_dirt",
        "minecraft:snow_block",
        "minecraft:soul_sand",
        "minecraft:soul_soil",
        "minecraft:stone",
        "minecraft:tuff"
    );

    private static final Set<String> ABSOLUTELY_FORBIDDEN = Set.of(
        "minecraft:ancient_debris",
        "minecraft:amethyst_block",
        "minecraft:anvil",
        "minecraft:barrel",
        "minecraft:beacon",
        "minecraft:blast_furnace",
        "minecraft:bookshelf",
        "minecraft:brewing_stand",
        "minecraft:budding_amethyst",
        "minecraft:chest",
        "minecraft:chipped_anvil",
        "minecraft:chiseled_bookshelf",
        "minecraft:coal_block",
        "minecraft:conduit",
        "minecraft:crafting_table",
        "minecraft:crying_obsidian",
        "minecraft:damaged_anvil",
        "minecraft:diamond_block",
        "minecraft:dispenser",
        "minecraft:dragon_egg",
        "minecraft:dropper",
        "minecraft:emerald_block",
        "minecraft:enchanting_table",
        "minecraft:ender_chest",
        "minecraft:furnace",
        "minecraft:gilded_blackstone",
        "minecraft:gold_block",
        "minecraft:hopper",
        "minecraft:jukebox",
        "minecraft:lapis_block",
        "minecraft:lodestone",
        "minecraft:netherite_block",
        "minecraft:note_block",
        "minecraft:observer",
        "minecraft:piston",
        "minecraft:raw_gold_block",
        "minecraft:redstone_block",
        "minecraft:redstone_lamp",
        "minecraft:respawn_anchor",
        "minecraft:sculk_catalyst",
        "minecraft:sculk_sensor",
        "minecraft:sculk_shrieker",
        "minecraft:smithing_table",
        "minecraft:smoker",
        "minecraft:sponge",
        "minecraft:sticky_piston",
        "minecraft:tnt",
        "minecraft:trapped_chest",
        "minecraft:wet_sponge"
    );

    private ScaffoldMaterialPolicy() {
    }

    public static boolean isAbsolutelyForbidden(String itemId) {
        String normalized = normalize(itemId);
        return ABSOLUTELY_FORBIDDEN.contains(normalized)
            || normalized.endsWith("_ore")
            || normalized.endsWith("_shulker_box")
            || normalized.endsWith("_command_block");
    }

    public static List<String> orderUsableIds(Collection<String> itemIds) {
        List<String> usable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String itemId : itemIds) {
            String normalized = normalize(itemId);
            if (!isAbsolutelyForbidden(normalized) && seen.add(normalized)) {
                usable.add(normalized);
            }
        }
        usable.sort(Comparator.comparingInt(id -> costTier(id).ordinal()));
        return List.copyOf(usable);
    }

    public static List<String> withoutTargetIds(
        Collection<String> itemIds,
        Collection<String> targetIds
    ) {
        Set<String> targets = new HashSet<>();
        if (targetIds != null) {
            for (String targetId : targetIds) {
                targets.add(normalize(targetId));
            }
        }
        return orderUsableIds(itemIds).stream()
            .filter(itemId -> !targets.contains(itemId))
            .toList();
    }

    public static CostTier costTier(String itemId) {
        String normalized = normalize(itemId);
        if (CHEAPEST.contains(normalized)) {
            return CostTier.CHEAPEST;
        }
        if (normalized.equals("minecraft:iron_block")
            || normalized.equals("minecraft:obsidian")
            || normalized.equals("minecraft:quartz_block")
            || normalized.contains("copper")) {
            return CostTier.EXPENSIVE;
        }
        return CostTier.COMMON;
    }

    private static String normalize(String itemId) {
        if (itemId == null) {
            return "";
        }
        return itemId.trim().toLowerCase(Locale.ROOT);
    }
}
