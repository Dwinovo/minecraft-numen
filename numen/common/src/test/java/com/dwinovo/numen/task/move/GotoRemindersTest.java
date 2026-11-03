package com.dwinovo.numen.task.move;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去处写错时的提醒怎么组成:说事实,再给能照抄的写法;每一句里的 {@code numen.move.walk} 都是完整的一次调用,坐标加距离。
 */
class GotoRemindersTest {

    private static final BlockPos FURNACE = new BlockPos(120, 64, -35);

    /** 四面封死:每一面前面是什么都列出来,离她最近的那一面给出挖开它的调用。 */
    @Test
    void aSealedBlockListsEveryCoverAndTheNearestToDig() {
        List<GotoReminders.Cover> covers = List.of(
                new GotoReminders.Cover("west", "stone", FURNACE.west()),
                new GotoReminders.Cover("up", "dirt", FURNACE.above()));
        String said = GotoReminders.sealed(FURNACE, "furnace", covers);
        assertTrue(said.startsWith("120,64,-35 (furnace) is walled in on every side"), said);
        assertTrue(said.contains("west stone at 119,64,-35, up dirt at 120,65,-35."), said);
        assertTrue(said.contains("the west one is nearest me: `numen.work.dig({x = 119, y = 64, z = -35})`"), said);
    }

    /** use 指向空气或水:没有可点的,给停在附近的写法;有敞开的面却无处可站:说哪几面敞开。 */
    @Test
    void nothingToClickAndNowhereToStandSayWhy() {
        String air = GotoReminders.nothingToUse(FURNACE, "air");
        assertTrue(air.startsWith("120,64,-35 is air — nothing there to click"), air);
        assertTrue(air.contains("`numen.move.walk({x = 120, y = 64, z = -35}, 2)`"), air);
        String nowhere = GotoReminders.nowhereToStand(FURNACE, "chest", List.of("up", "north"));
        assertTrue(nowhere.contains("is open on up, north, but there is nowhere within reach"), nowhere);
    }
}
