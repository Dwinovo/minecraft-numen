package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.network.payload.StopProgramPayload;
import com.dwinovo.numen.program.ModuleSync;
import com.dwinovo.numen.program.ProgramUplink;
import com.dwinovo.numen.program.RunResult;
import com.dwinovo.numen.script.Modules;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端的工具口:跑 Lua 的那个工具的调用整段送去服务端;切断时这一批作废、服务端上的程序当场停下,它晚到的回执不丢,交给收件的人
 * (作为一条事件进她的收件箱);没被切断的程序回执就是那个调用的结果。
 */
class CompanionToolPortTest {

    private static final UUID HER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeAll
    static void tools() {
        if (ToolRegistry.resolve("lua") == null) {
            ToolRegistry.register(new ScriptTool());
        }
    }

    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private final ProgramUplink uplink = new ProgramUplink(new ModuleSync(), sent::add, uuid -> Modules.factory());
    private final List<String> afterCut = new ArrayList<>();
    private final CompanionToolPort port = new CompanionToolPort(HER, () -> () -> HER, uplink,
            (program, receipt) -> afterCut.add(program + " | " + receipt));
    private final List<String> finished = new ArrayList<>();
    private final ToolPort.Sink sink = new ToolPort.Sink() {
        @Override
        public void started(LlmToolCall call) {
        }

        @Override
        public void finished(LlmToolCall call, String resultJson) {
            finished.add(call.id() + " " + resultJson);
        }

        @Override
        public void settled() {
        }
    };

    private static LlmToolCall program(String id) {
        return new LlmToolCall(id, "lua", ScriptTool.args("numen.work.dig({x = 1, y = 2, z = 3})").toString());
    }

    private static RunResult ended(String receipt) {
        return new RunResult.Ended(new Program.Outcome(receipt,
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null));
    }

    @Test
    void aProgramIsSentWholeAndItsReceiptIsTheCallsResult() {
        port.run(List.of(program("c1")), sink);
        RunProgramPayload run = assertInstanceOf(RunProgramPayload.class, sent.get(0));
        assertEquals("c1", run.programId());
        assertEquals("numen.work.dig({x = 1, y = 2, z = 3})", run.code());

        uplink.deliver("c1", ended("{\"success\":true,\"message\":\"done\"}"));
        assertEquals(List.of("c1 {\"success\":true,\"message\":\"done\"}"), finished);
        assertTrue(afterCut.isEmpty());
    }

    @Test
    void aProgramCutOffByTheStopButtonStillHandsInItsReceiptAsAnEventLater() {
        port.run(List.of(program("c1")), sink);

        assertEquals(List.of("c1"), port.cancel(true));
        StopProgramPayload stop = assertInstanceOf(StopProgramPayload.class, sent.get(1));
        assertTrue(stop.cutOff() && stop.stopBody() && stop.programId().equals("c1"));
        assertInstanceOf(CancelTasksPayload.class, sent.get(2), "the stop button also stops the body");
        assertTrue(finished.isEmpty() && afterCut.isEmpty(), "the batch is void: nothing is handed to the model now");

        String receipt = ToolOutcome.failure("The script stopped at line 1 (numen.work.dig) after 3 calls: this turn "
                + "was cut off; t8 was stopped too.");
        uplink.deliver("c1", ended(receipt));
        assertEquals(List.of("c1 | " + receipt), afterCut, "the receipt the server wrote, as it is");
        assertTrue(finished.isEmpty(), "still not a result of the void batch");
    }

    @Test
    void aCutOffThatIsNotTheStopButtonDoesNotStopTheBody() {
        port.run(List.of(program("c1")), sink);
        port.cancel(false);
        assertEquals(2, sent.size());
        assertFalse(sent.stream().anyMatch(p -> p instanceof CancelTasksPayload));
    }

    @Test
    void whenTheConnectionIsGoneNothingComesLaterToHand() {
        port.run(List.of(program("c1")), sink);
        port.cancel(false);
        uplink.disconnected();
        uplink.deliver("c1", ended("{\"success\":false,\"message\":\"x\"}"));
        assertTrue(afterCut.isEmpty());
    }
}
