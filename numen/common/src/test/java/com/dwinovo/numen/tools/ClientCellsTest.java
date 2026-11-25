package com.dwinovo.numen.tools;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Dispatcher;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次客户端函数返回一整份格({@link LuaCodecs#MAX_CELLS} 是单次上限),程序带着她的模块({@link Modules#of})跑:答复里每个带方法的值(Block 的位置是 numen.shape 的 Pos)
 * 取元表时不重复读模块,一次读满不触发整段的字符串上限。
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

    /** 主人客户端的样子:客户端函数就在这个进程里执行,答复照原样交回。 */
    private static final ClientTransport CLIENT = new ClientTransport() {
        @Override
        public void request(Request request, java.util.function.Consumer<String> done) {
            Dispatcher.client(request.function(), request.arguments(), request.companion(), null, done);
        }

        @Override
        public void cancel(String callId) { }
    };

    @BeforeAll
    static void register() {
        NumenApiFixture.install();
        ApiRegistry.register("gt", "gt_cells", "Test fixture.", Reads.class);
    }

    @Test
    void aWholeProjectionComesBackInOneCall() {
        for (int count : new int[] {5844, LuaCodecs.MAX_CELLS}) {
            UUID her = UUID.randomUUID();
            ApiTester.Run run = ApiTester.run(null, her, "local c = gt.gt_cells.cells(" + count + ") return #c",
                    Modules.of(her, java.util.Map.of(), java.util.Set.of()), CLIENT);
            assertTrue(run.ok(), count + ": " + run.message());
            assertEquals(count, run.returned().getAsInt());
        }
    }
}
