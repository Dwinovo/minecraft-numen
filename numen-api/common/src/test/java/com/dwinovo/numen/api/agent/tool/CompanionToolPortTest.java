package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.loop.ToolPort;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.script.ScriptCall;
import com.dwinovo.numen.api.program.ProgramHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴的工具口:跑 Lua 的那个工具的调用整段交给 {@link ProgramHost};切断时这一批作废、程序当场停下,它晚到的回执不丢,交给收件的人
 * (作为一条事件进她的收件箱);没被切断的程序回执就是那个调用的结果。
 */
class CompanionToolPortTest {

    private static final UUID HER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeAll
    static void tools() {
        if (ToolRegistry.resolve("Lua") == null) {
            ToolRegistry.register(new ScriptTool());
        }
        if (ToolRegistry.resolve(AskUserQuestionTool.NAME) == null) {
            ToolRegistry.register(new AskUserQuestionTool());
        }
    }

    /** 记下工具口对程序宿主做的每件事;等答复的程序按编号记着,{@link #deliver} 扮宿主交回结局。 */
    private static final class Host implements ProgramHost {
        final List<String> did = new ArrayList<>();
        final Map<String, Consumer<Program.Outcome>> waiting = new HashMap<>();

        @Override
        public void run(UUID companion, String programId, String code, Consumer<Program.Outcome> done) {
            did.add("run " + programId + " " + code);
            waiting.put(programId, done);
        }

        @Override
        public void afterwards(String programId, Consumer<Program.Outcome> late) {
            did.add("afterwards " + programId);
            waiting.computeIfPresent(programId, (id, was) -> late);
        }

        @Override
        public void forget(String programId) {
            did.add("forget " + programId);
            waiting.remove(programId);
        }

        @Override
        public void interrupt(UUID companion, String programId, String why) {
            did.add("interrupt " + programId + " " + why);
        }

        @Override
        public void cutOff(UUID companion, String programId, boolean stopBody) {
            did.add("cutOff " + programId + " " + stopBody);
        }

        @Override
        public void stopBody(UUID companion) {
            did.add("stopBody");
        }

        void deliver(String programId, Program.Outcome outcome) {
            Consumer<Program.Outcome> to = waiting.remove(programId);
            if (to != null) {
                to.accept(outcome);
            }
        }
    }

    private final Host host = new Host();
    private final QuestionDesk desk = new QuestionDesk();
    private final List<String> afterCut = new ArrayList<>();
    private final CompanionToolPort port = new CompanionToolPort(HER, () -> new com.dwinovo.numen.api.agent.tool.ToolAnchor() {
        @Override
        public UUID entityUuid() {
            return HER;
        }

        @Override
        public com.dwinovo.numen.api.agent.memory.NoteBook notes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.dwinovo.numen.api.agent.skill.SkillRegistry skills() {
            throw new UnsupportedOperationException();
        }

        @Override
        public QuestionDesk questions() {
            return desk;
        }

        @Override
        public boolean channelVisit() {
            return false;
        }
    }, host, new ToolBook() {
        @Override
        public List<NumenTool> all() {
            return ToolRegistry.all();
        }

        @Override
        public NumenTool resolve(String name) {
            return ToolRegistry.resolve(name);
        }
    }, (program, receipt) -> afterCut.add(program + " | " + receipt));
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
        return new LlmToolCall(id, "Lua", ScriptTool.args("numen.work.dig({x = 1, y = 2, z = 3})", "Dig one block").toString());
    }

    private static Program.Outcome ended(String receipt) {
        return new Program.Outcome(receipt, new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null);
    }

    @Test
    void aProgramIsHandedOverWholeAndItsReceiptIsTheCallsResult() {
        port.run(List.of(program("c1")), sink);
        assertEquals(List.of("run c1 numen.work.dig({x = 1, y = 2, z = 3})"), host.did);

        host.deliver("c1", ended("{\"success\":true,\"message\":\"done\"}"));
        assertEquals(List.of("c1 {\"success\":true,\"message\":\"done\"}"), finished);
        assertTrue(afterCut.isEmpty());
    }

    @Test
    void aProgramCutOffByTheStopButtonStillHandsInItsReceiptAsAnEventLater() {
        port.run(List.of(program("c1")), sink);

        assertEquals(List.of("c1"), port.cancel(true));
        assertEquals(List.of("run c1 numen.work.dig({x = 1, y = 2, z = 3})", "cutOff c1 true", "afterwards c1",
                "stopBody"), host.did, "the stop button cuts the program off and also stops the body");
        assertTrue(finished.isEmpty() && afterCut.isEmpty(), "the batch is void: nothing is handed to the model now");

        String receipt = ToolOutcome.failure("The script stopped at line 1 (numen.work.dig) after 3 calls: this turn "
                + "was cut off; t8 was stopped too.");
        host.deliver("c1", ended(receipt));
        assertEquals(List.of("c1 | " + receipt), afterCut, "the receipt the server wrote, as it is");
        assertTrue(finished.isEmpty(), "still not a result of the void batch");
    }

    @Test
    void aCutOffThatIsNotTheStopButtonDoesNotStopTheBody() {
        port.run(List.of(program("c1")), sink);
        port.cancel(false);
        assertTrue(host.did.stream().noneMatch(did -> did.equals("stopBody")), host.did.toString());
    }

    private static LlmToolCall question(String id) {
        return new LlmToolCall(id, AskUserQuestionTool.NAME, "{\"questions\":[{\"question\":\"Which?\",\"header\":\"h\","
                + "\"options\":[{\"label\":\"a\",\"description\":\"\"},{\"label\":\"b\",\"description\":\"\"}]}]}");
    }

    @Test
    void aQuestionStaysPendingUntilTheOwnerAnswersAndThenItsAnswerIsTheCallsResult() {
        port.run(List.of(question("q1")), sink);
        assertNotNull(desk.pending());
        assertTrue(finished.isEmpty(), "the call waits for the owner");

        desk.pending().pick(1);
        assertEquals(1, finished.size());
        assertTrue(finished.get(0).startsWith("q1 "), finished.get(0));
        assertTrue(ToolOutcome.modelText(finished.get(0).substring(3)).contains("\"Which?\"=\"b\""), finished.get(0));
        assertNull(desk.pending());
    }

    @Test
    void theStopButtonEndsAPendingQuestionLikeAnyOtherCallAndTheLateAnswerGoesNowhere() {
        port.run(List.of(question("q1")), sink);
        Asking asking = desk.pending();

        assertEquals(List.of("q1"), port.cancel(true));
        assertNull(desk.pending(), "the panel is taken down");
        asking.pick(0);
        assertTrue(finished.isEmpty(), "the batch is void: no result is handed to the model");
        assertEquals(List.of("forget q1", "stopBody"), host.did);
    }

    @Test
    void anUrgentInputSettlesAPendingQuestionWithWhatInterruptedIt() {
        port.run(List.of(question("q1"), question("q2")), sink);
        assertEquals(List.of(), finished);

        port.arrived(new com.dwinovo.numen.api.agent.inbox.EventQueue.Entry(
                com.dwinovo.numen.api.agent.inbox.EventTypes.OWNER_HURT, "<event>x</event>", 0, true), true);

        assertEquals(2, finished.size(), "the question is settled and the rest of the batch is not run");
        assertTrue(finished.get(0).contains("Stopped before your owner answered (an urgent owner_hurt event arrived)"),
                finished.get(0));
        assertTrue(finished.get(1).contains("Not run"), finished.get(1));
        assertNull(desk.pending());
    }

    @Test
    void aLeavingCompanionCutsAPendingQuestionOffThroughTheSamePath() {
        port.run(List.of(question("q1")), sink);
        port.cancel(false);
        assertNull(desk.pending());
        assertTrue(host.did.stream().noneMatch(did -> did.equals("stopBody")));
    }
}
