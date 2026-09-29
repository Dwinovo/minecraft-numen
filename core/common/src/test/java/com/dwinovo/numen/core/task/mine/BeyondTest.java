package com.dwinovo.numen.core.task.mine;

import java.util.List;

import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区外的目标怎么说给模型:几个、最近的在哪、多远,以及照着就能做的下一步。 */
class BeyondTest {

    private static final BlockPos FROM = new BlockPos(0, 64, 0);
    private static final BlockPos NEAR = new BlockPos(60, 64, 0);
    private static final BlockPos FAR = new BlockPos(0, 64, 90);

    @Test
    void whatLiesBeyondSaysHowManyTheNearestAndWhatToDo() {
        Beyond seen = new Beyond(List.of(FAR, NEAR), false);
        assertEquals("2 more lie beyond it, the nearest at 60,64,0, about 60 blocks from me: move_goto there first"
                + " (x:60 y:64 z:0 arrive:near near:" + Beyond.NEAR + "), then work_mine again", seen.more(FROM));
        assertTrue(new Beyond(List.of(FAR, NEAR), true).more(FROM).startsWith("at least 2 more lie beyond it"),
                "查询凑够就停时个数只是下限");
        assertTrue(seen.named(FROM).startsWith("2 of the named cells lie beyond it and were left, the nearest at"
                + " 60,64,0"), seen.named(FROM));
    }

    @Test
    void theNearestIsCountedFromWhereSheStandsNow() {
        Beyond seen = new Beyond(List.of(NEAR, FAR), false);
        assertTrue(seen.more(new BlockPos(0, 64, 80)).contains("the nearest at 0,64,90, about 10 blocks from me"));
    }

    @Test
    void onlyWhatIsStillThereIsTold() {
        Beyond seen = new Beyond(List.of(NEAR, FAR), false);
        assertTrue(seen.keep(p -> false).isEmpty());
        assertEquals(List.of(FAR), seen.keep(FAR::equals).cells());
        assertTrue(Beyond.NONE.isEmpty());
    }

    @Test
    void aGroupWhollyBeyondTheAreaIsRefusedWithWhereItIsAndHowToGetThere() {
        WorkArea area = new WorkArea(FROM, WorkArea.RADIUS);
        String one = Beyond.groupsOutside(List.of("g3"), false, area, NEAR);
        assertEquals("group g3 lies wholly beyond my work area (within " + WorkArea.RADIUS + " blocks of 0,64,0), so I"
                + " did not start; the nearest of its cells is at 60,64,0, about 60 blocks away. move_goto there first"
                + " (x:60 y:64 z:0 arrive:near near:" + Beyond.NEAR + "), then work_mine again (group ids stay good until your next"
                + " scan_blocks).", one);
        String two = Beyond.groupsOutside(List.of("g3", "g5"), true, area, NEAR);
        assertTrue(two.startsWith("groups g3, g5 lie wholly beyond") && two.contains("the nearest of their cells")
                && two.endsWith("or leave them out to dig the other groups from here."), two);
    }
}
