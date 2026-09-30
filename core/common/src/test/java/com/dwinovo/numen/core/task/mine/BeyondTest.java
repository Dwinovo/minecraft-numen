package com.dwinovo.numen.core.task.mine;

import java.util.List;

import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

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
        Beyond seen = new Beyond(List.of(FAR, NEAR));
        assertEquals("2 scanned cells of ores/g3 lie beyond it and were left, the nearest at 60,64,0, about 60 blocks"
                + " from me: move_goto there first (x:60 y:64 z:0 arrive:near near:" + Beyond.NEAR + "), then work_mine"
                + " again", seen.told("ores/g3", FROM));
    }

    @Test
    void theNearestIsCountedFromWhereSheStandsNow() {
        Beyond seen = new Beyond(List.of(NEAR, FAR));
        assertTrue(seen.told("ores", new BlockPos(0, 64, 80)).contains("the nearest at 0,64,90, about 10 blocks from me"));
    }

    @Test
    void onlyWhatIsStillThereIsTold() {
        Beyond seen = new Beyond(List.of(NEAR, FAR));
        assertTrue(seen.keep(p -> false).isEmpty());
        assertEquals(List.of(FAR), seen.keep(FAR::equals).cells());
        assertTrue(Beyond.NONE.isEmpty());
    }

    @Test
    void anAreaWhollyBeyondTheWorkAreaIsRefusedWithWhereItIsAndHowToGetThere() {
        WorkArea work = WorkArea.at(Level.OVERWORLD, FROM, WorkArea.RADIUS);
        assertEquals("all 3 scanned cells of ores/g3 lie wholly beyond my work area (within " + WorkArea.RADIUS
                + " blocks of 0,64,0), so I did not start; the nearest is at 60,64,0, about 60 blocks away. move_goto"
                + " there first (x:60 y:64 z:0 arrive:near near:" + Beyond.NEAR + "), then work_mine again.",
                Beyond.areaOutside("ores/g3", 3, work, NEAR));
    }
}
