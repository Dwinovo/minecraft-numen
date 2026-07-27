package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.ScaffoldMaterialPolicy;
import java.util.List;

public final class ScaffoldMaterialPolicyTest {
    @Test
    void verifiedRuntimeBehavior() {
        List<String> forbidden = List.of(
            "minecraft:diamond_ore",
            "minecraft:deepslate_diamond_ore",
            "minecraft:diamond_block",
            "minecraft:ancient_debris",
            "minecraft:netherite_block",
            "minecraft:beacon",
            "minecraft:emerald_ore",
            "minecraft:deepslate_emerald_ore",
            "minecraft:emerald_block",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "minecraft:nether_gold_ore",
            "minecraft:gold_block",
            "minecraft:raw_gold_block",
            "minecraft:lapis_ore",
            "minecraft:deepslate_lapis_ore",
            "minecraft:lapis_block",
            "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore",
            "minecraft:redstone_block"
        );

        for (String id : forbidden) {
            require(
                ScaffoldMaterialPolicy.isAbsolutelyForbidden(id),
                id + " must never be usable as temporary scaffolding"
            );
        }

        List<String> ordered = ScaffoldMaterialPolicy.orderUsableIds(
            List.of(
                "minecraft:diamond_block",
                "minecraft:beacon",
                "minecraft:dirt",
                "minecraft:ancient_debris",
                "minecraft:netherite_block"
            )
        );
        require(ordered.equals(List.of("minecraft:dirt")), "forbidden candidates leaked: " + ordered);

        require(
            ScaffoldMaterialPolicy.orderUsableIds(forbidden).isEmpty(),
            "valuable blocks must not become a last-resort fallback"
        );

        List<String> prioritized = ScaffoldMaterialPolicy.orderUsableIds(
            List.of(
                "minecraft:iron_block",
                "minecraft:oak_planks",
                "minecraft:stone",
                "minecraft:obsidian",
                "minecraft:cobblestone",
                "minecraft:bricks",
                "minecraft:copper_block"
            )
        );
        require(
            prioritized.equals(
                List.of(
                    "minecraft:stone",
                    "minecraft:cobblestone",
                    "minecraft:oak_planks",
                    "minecraft:bricks",
                    "minecraft:iron_block",
                    "minecraft:obsidian",
                    "minecraft:copper_block"
                )
            ),
            "scaffold cost tiers are not cheapest -> common -> expensive: " + prioritized
        );

        List<String> miningTargetExcluded = ScaffoldMaterialPolicy.withoutTargetIds(
            List.of(
                "minecraft:mangrove_log",
                "minecraft:dirt",
                "minecraft:oak_planks"
            ),
            List.of("minecraft:mangrove_log")
        );
        require(
            miningTargetExcluded.equals(List.of("minecraft:dirt", "minecraft:oak_planks")),
            "the active mining target block must not be selected as a temporary scaffold: "
                + miningTargetExcluded
        );

        List<String> functionalOrRare = List.of(
            "minecraft:amethyst_block",
            "minecraft:budding_amethyst",
            "minecraft:coal_block",
            "minecraft:crying_obsidian",
            "minecraft:dragon_egg",
            "minecraft:enchanting_table",
            "minecraft:bookshelf",
            "minecraft:ender_chest",
            "minecraft:chest",
            "minecraft:barrel",
            "minecraft:hopper",
            "minecraft:dispenser",
            "minecraft:dropper",
            "minecraft:observer",
            "minecraft:piston",
            "minecraft:sticky_piston",
            "minecraft:crafting_table",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:brewing_stand",
            "minecraft:anvil",
            "minecraft:smithing_table",
            "minecraft:respawn_anchor",
            "minecraft:lodestone",
            "minecraft:conduit",
            "minecraft:sponge",
            "minecraft:wet_sponge",
            "minecraft:gilded_blackstone",
            "minecraft:sculk_catalyst",
            "minecraft:sculk_sensor",
            "minecraft:sculk_shrieker"
        );
        for (String id : functionalOrRare) {
            require(
                ScaffoldMaterialPolicy.isAbsolutelyForbidden(id),
                id + " is a rare or functional block and must never be sacrificed"
            );
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
