package com.dwinovo.numen.core.gear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code gear remove --slot} 的名字怎么展开:{@code armor} 是四件原版甲的别名,别的名字就是它自己。槽名随身体而定,
 * 这里只钉展开,是不是真有这个槽由 {@link Wardrobe} 在身上答。{@code gear wear --slot armor} 与 {@code gear remove}
 * 什么都不给的拒绝,从 {@code command} 入口在 GameTest 里验(InventoryGameTests)。
 */
class WardrobeNamesTest {

    @Test
    void armorExpandsToAllFourPieces() {
        assertEquals(List.of("head", "chest", "legs", "feet"), Wardrobe.namesFor(Wardrobe.ARMOR));
    }

    @Test
    void anyOtherSlotNameIsItself() {
        assertEquals(List.of(Wardrobe.MAINHAND), Wardrobe.namesFor(Wardrobe.MAINHAND));
        assertEquals(List.of("curios:ring"), Wardrobe.namesFor("curios:ring"));
    }
}
