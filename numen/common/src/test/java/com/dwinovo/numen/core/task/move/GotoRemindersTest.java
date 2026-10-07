package com.dwinovo.numen.core.task.move;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去处写错时的提醒怎么组成:说事实,再给能照抄的写法;每一句里的 {@code numen.move.to} 都是完整的一次调用,坐标加选项。
 */
class GotoRemindersTest {

    private static final BlockPos FURNACE = new BlockPos(120, 64, -35);

    @Test
    void aCopyableCallSpellsEveryCoordinateAndTheOptions() {
        assertEquals("`numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})`", GotoReminders.call(FURNACE, "arrive = \"use\""));
        assertEquals("`numen.move.to({x = 120, y = 64, z = -35})`", GotoReminders.call(FURNACE, ""));
    }

    /**
     * at 指向一格方块:说它是什么、站不进去,给用它、站上去、停在附近、挖进去四种写法;站上去照抄的是它上面脚所在的那一格,
     * 站不上去就不给这一种。
     */
    @Test
    void anOccupiedCellNamesTheBlockAndEveryWayToWriteIt() {
        String said = GotoReminders.occupied(FURNACE, "furnace", FURNACE.above());
        assertTrue(said.startsWith("120,64,-35 is furnace — no room to stand in it"), said);
        assertTrue(said.contains("To use it: `numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})`"), said);
        assertTrue(said.contains("to stand on top of it: `numen.move.to({x = 120, y = 65, z = -35})`;"), said);
        assertTrue(said.contains("to stop close by: `numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"near\"})`"), said);
        assertTrue(said.contains("add costs = {dig = true}"), said);
        String covered = GotoReminders.occupied(FURNACE, "furnace", null);
        assertTrue(!covered.contains("on top"), covered);
    }

    /** at 指向半空:说那一列的地面在哪一层,写成能照抄的一次调用;找不到地面就不提。 */
    @Test
    void midAirNamesTheGroundOfThatColumnWhenThereIsOne() {
        String withGround = GotoReminders.midAir(new BlockPos(120, 70, -35), new BlockPos(120, 64, -35));
        assertTrue(withGround.startsWith("120,70,-35 is in mid-air"), withGround);
        assertTrue(withGround.contains("the ground in that column is at y=64: `numen.move.to({x = 120, y = 64, z = -35})`"),
                withGround);
        assertTrue(withGround.contains("Give {x = …, z = …} alone"), withGround);
        String without = GotoReminders.midAir(new BlockPos(120, 70, -35), null);
        assertTrue(!without.contains("ground in that column"), without);
    }

    /** 四面封死:每一面前面是什么都列出来,离她最近的那一面给出挖开它的调用,再照抄 arrive "use"。 */
    @Test
    void aSealedBlockListsEveryCoverAndTheNearestToDig() {
        List<GotoReminders.Cover> covers = List.of(
                new GotoReminders.Cover("west", "stone", FURNACE.west()),
                new GotoReminders.Cover("up", "dirt", FURNACE.above()));
        String said = GotoReminders.sealed(FURNACE, "furnace", covers);
        assertTrue(said.startsWith("120,64,-35 (furnace) is walled in on every side"), said);
        assertTrue(said.contains("west stone at 119,64,-35, up dirt at 120,65,-35."), said);
        assertTrue(said.contains("the west one is nearest me: `numen.move.to({x = 119, y = 64, z = -35}, {arrive = \"dig\"})`, "
                + "then `numen.work.dig({x = 119, y = 64, z = -35})`"), said);
        assertTrue(said.endsWith("`numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})` again."), said);
    }

    /** use 指向空气或水:没有可点的,给停在附近的写法;有敞开的面却无处可站:说哪几面敞开。 */
    @Test
    void nothingToClickAndNowhereToStandSayWhy() {
        String air = GotoReminders.nothingToUse(FURNACE, "air");
        assertTrue(air.startsWith("120,64,-35 is air — nothing there to click"), air);
        assertTrue(air.contains("`numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"near\"})`"), air);
        String nowhere = GotoReminders.nowhereToStand(FURNACE, "chest", List.of("up", "north"));
        assertTrue(nowhere.contains("is open on up, north, but there is nowhere within reach"), nowhere);
    }
}
