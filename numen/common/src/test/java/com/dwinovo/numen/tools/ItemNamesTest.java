package com.dwinovo.numen.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 按名字找物品的排法:整个名字、开头、含有、id,依次靠前,大小写不计,条数封顶。 */
class ItemNamesTest {

    private static ItemNames.Match m(String item, String name) {
        return new ItemNames.Match(item, name);
    }

    private static List<String> ids(ItemNames.Matches found) {
        return found.items().stream().map(ItemNames.Match::item).toList();
    }

    @Test
    void theWholeNameComesBeforeNamesThatStartWithIt() {
        ItemNames.Matches found = ItemNames.find("铁锭", Stream.of(
                m("minecraft:iron_block", "铁块"),
                m("minecraft:iron_ingot", "铁锭"),
                m("mod:iron_ingot_tool", "铁锭工具"),
                m("mod:old_iron", "旧铁锭"),
                m("mod:other", "金锭")));

        assertEquals(List.of("minecraft:iron_ingot", "mod:iron_ingot_tool", "mod:old_iron"), ids(found));
    }

    @Test
    void anIdMatchesWhenNoNameDoesAndCaseIsIgnored() {
        ItemNames.Matches found = ItemNames.find("  RAIL ", Stream.of(
                m("minecraft:rail", "Rail"),
                m("minecraft:powered_rail", "Powered Rail"),
                m("mod:track", "Track"),
                m("mod:rail_cart", "Cart")));

        assertEquals(List.of("minecraft:rail", "minecraft:powered_rail", "mod:rail_cart"), ids(found));
    }

    @Test
    void nothingMatchesAnEmptyQuery() {
        assertEquals(List.of(), ids(ItemNames.find("  ", Stream.of(m("minecraft:stone", "Stone")))));
    }

    @Test
    void theListIsCappedAndSaysHowManyMore() {
        List<ItemNames.Match> many = new ArrayList<>();
        for (int i = 0; i < ItemNames.MAX_MATCHES + 3; i++) {
            many.add(m("mod:ore_" + i, "Ore " + i));
        }

        ItemNames.Matches found = ItemNames.find("ore", many.stream());

        assertEquals(ItemNames.MAX_MATCHES, found.items().size());
        assertEquals(Optional.of(3), found.omitted());
    }
}
