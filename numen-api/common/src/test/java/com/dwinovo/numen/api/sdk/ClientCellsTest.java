package com.dwinovo.numen.api.sdk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次客户端函数返回一整份格({@link LuaCodecs#MAX_CELLS} 是单次上限):答复经线上的样子交给脚本,一次读满不触发整段的字符串上限
 * ({@code ScriptLimits.STRING_BYTES}),计量与数据量成正比。
 */
class ClientCellsTest {

    public static final class Reads {

        private Reads() {}

        public record Count(@Doc("How many cells.") int count) {}

        @Fn("Cells as a client function returns them.")
        @Example("gt.gt_cells.cells(10)")
        public static List<BlockAt> cells(ClientCall call, Count args) {
            List<BlockAt> out = new ArrayList<>();
            for (int i = 0; i < args.count(); i++) {
                out.add(new BlockAt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                        new net.minecraft.core.BlockPos(i % 40, -60 + i / 1800, i / 40 % 45)));
            }
            return out;
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_cells", Reads.class);
    }

    @Test
    void aWholeProjectionComesBackInOneCall() {
        for (int count : new int[] {5844, LuaCodecs.MAX_CELLS}) {
            ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(),
                    "local c = gt.gt_cells.cells(" + count + ") return #c");
            assertTrue(run.ok(), count + ": " + run.message());
            assertEquals(count, run.returned().getAsInt());
        }
    }
}
