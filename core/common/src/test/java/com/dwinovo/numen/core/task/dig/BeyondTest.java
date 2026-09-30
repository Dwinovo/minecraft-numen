package com.dwinovo.numen.core.task.dig;

import java.util.List;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区外的格怎么说给模型:几格、最近的在哪、多远,以及照抄就能开路的写法。 */
class BeyondTest {

    private static final BlockPos FROM = new BlockPos(0, 64, 0);
    private static final BlockPos NEAR = new BlockPos(60, 64, 0);
    private static final BlockPos FAR = new BlockPos(0, 64, 90);
    private static final Cells BOTH = Cells.of(List.of(FAR, NEAR));

    @Test
    void anAreaBeyondIsReachedByNameWithARouteOrAGoto() {
        Beyond seen = new Beyond(BOTH, AreaRef.parse("ores/g3"), "ores/g3", "ores/g3");
        assertEquals("2 cell(s) of ores/g3 lie beyond it and are left, the nearest at 60,64,0, about 60 blocks from me."
                + " To dig there, open the way first: `route new ores --to ores/g3 --arrive dig --alter natural`, "
                + "`route plan ores` to see what the way changes, `move go ores` — or straight away move_goto "
                + "area:ores/g3 arrive:dig alter:natural — then `work dig ores/g3` again.", seen.told(FROM));
    }

    @Test
    void cellsBeyondAreReachedAtTheNearestOne() {
        Beyond seen = new Beyond(BOTH, null, "60,64,0 0,64,90", "60 64 0 0 64 90");
        String told = seen.told(new BlockPos(0, 64, 80));
        assertTrue(told.contains("the nearest at 0,64,90, about 10 blocks from me"), told);
        assertTrue(told.contains("`route new dig --to 0 64 90 --arrive dig --alter natural`")
                && told.contains("move_goto x:0 y:64 z:90 arrive:dig alter:natural")
                && told.endsWith("then `work dig 60 64 0 0 64 90` again."), told);
    }

    @Test
    void somethingWhollyBeyondTheWorkAreaIsRefusedWithWhereItIsAndHowToGetThere() {
        WorkArea work = WorkArea.around(Level.OVERWORLD, FROM);
        Beyond seen = new Beyond(BOTH, AreaRef.parse("ores"), "ores", "ores");
        assertTrue(seen.refusal(work).startsWith("all 2 cell(s) of ores lie beyond my work area (within "
                + WorkArea.RADIUS + " blocks of 0,64,0), so I did not start; the nearest is at 60,64,0, about 60 "
                + "blocks away. To dig there, open the way first: `route new ores --to ores"), seen.refusal(work));
    }

    @Test
    void nothingBeyondIsEmpty() {
        assertTrue(new Beyond(Cells.EMPTY, null, "pit", "pit").isEmpty());
    }
}
