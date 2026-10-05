package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ScriptCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一轮里的调用按顺序执行:一个做完才派下一个。其中的程序整段在服务端跑,这里只管顺序:程序在跑的时候后面的调用等着;来了急件叫服务端
 * 让程序停在调用之间,程序被叫停回来,余下的调用逐条回"没执行";切断叫服务端当场停下它。
 *
 * <p>假的执行口把派出去的调用停在 {@link #pending} 里等测试替它回结果。
 */
class SerialCallsTest {

    private final Map<String, Consumer<SerialCalls.Settled>> pending = new LinkedHashMap<>();
    private final List<String> dispatched = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final List<String> reported = new ArrayList<>();
    private int settles;

    private final SerialCalls.Port port = new SerialCalls.Port() {
        @Override
        public void invoke(LlmToolCall call, Consumer<SerialCalls.Settled> done) {
            dispatched.add(call.id());
            pending.put(call.id(), done);
        }

        @Override
        public boolean isProgram(LlmToolCall call) {
            return "lua".equals(call.name());
        }

        @Override
        public void interrupt(LlmToolCall program, String why) {
            told.add("interrupt " + program.id() + ": " + why);
        }

        @Override
        public void cutOff(LlmToolCall program, boolean stopBody) {
            told.add("cut off " + program.id() + " stopBody=" + stopBody);
        }
    };

    private final SerialCalls calls = new SerialCalls(port);

    private final ToolPort.Sink sink = new ToolPort.Sink() {
        @Override
        public void started(LlmToolCall call) {
        }

        @Override
        public void finished(LlmToolCall call, String resultJson) {
            assertNull(results.put(call.id(), resultJson), call.id() + " 拿到了两个结果");
        }

        @Override
        public void settled() {
            settles++;
        }

        @Override
        public void called(LlmToolCall program, ScriptCall.Called called) {
            reported.add(program.id() + " " + called.function() + (called.kind() == null ? "" : " " + called.kind()));
        }
    };

    private static LlmToolCall tool(String id) {
        return new LlmToolCall(id, "skill", "{}");
    }

    private static LlmToolCall program(String id) {
        return new LlmToolCall(id, "lua", "{\"code\":\"x\"}");
    }

    private void answer(String id, String result) {
        pending.remove(id).accept(SerialCalls.Settled.of(result));
    }

    private static EventQueue.Entry ownerWords(String words) {
        return new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(words), 0, false);
    }

    @Test
    void theNextCallGoesOutOnlyWhenTheLastOneHasItsResult() {
        calls.run(List.of(tool("a"), tool("b")), sink);
        assertEquals(List.of("a"), dispatched);

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
        assertEquals(0, settles);

        answer("b", "{\"success\":true}");
        assertEquals(1, settles);
        assertEquals(List.of("a", "b"), List.copyOf(results.keySet()));
    }

    @Test
    void inputWhileAToolIsStillRunningDropsNothingAndTellsNoOne() {
        calls.run(List.of(tool("a"), tool("b")), sink);
        calls.arrived(ownerWords("先停一下"), true);
        assertEquals(List.of("a"), dispatched);
        assertTrue(results.isEmpty(), "工具本身有界短:等它的结果,输入跟下一次调用走");
        assertTrue(told.isEmpty(), "不是程序,没有谁要叫停");

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
    }

    @Test
    void anUrgentInputWhileAProgramRunsTellsTheServerOnceAndTheRestIsNotRun() {
        calls.run(List.of(program("p"), tool("after"), tool("later")), sink);
        assertEquals(List.of("p"), dispatched, "程序在服务端跑着,后面的等着");

        calls.arrived(new EventQueue.Entry(EventTypes.OWNER_HURT, "<event>危险</event>", 0, true), true);
        calls.arrived(ownerWords("等等"), true);
        assertEquals(List.of("interrupt p: an urgent owner_hurt event arrived"), told, "同一次只说一遍");
        assertTrue(results.isEmpty(), "程序是服务端在停,这里等它交回结局");

        pending.remove("p").accept(new SerialCalls.Settled("{\"success\":false}", null, List.of(
                new ScriptCall.Called("numen.move.go", "numen.move.go()", null, null)),
                "an urgent owner_hurt event arrived; t3 keeps running"));

        assertEquals(List.of("p numen.move.go"), reported, "每次调用的结局随回执送到");
        for (String skipped : List.of("after", "later")) {
            assertTrue(ToolOutcome.failed(results.get(skipped)), results.get(skipped));
            assertTrue(results.get(skipped).contains("Not run") && results.get(skipped)
                    .contains("t3 keeps running"), "原因取程序结局里结构化的那一句: " + results.get(skipped));
        }
        assertEquals(List.of("p"), dispatched);
        assertEquals(1, settles);
    }

    @Test
    void aNonUrgentInputDoesNotStopTheProgram() {
        calls.run(List.of(program("p")), sink);
        calls.arrived(new EventQueue.Entry(EventTypes.REFLEX, "<event>x</event>", 0, false), false);
        assertTrue(told.isEmpty());
    }

    @Test
    void aProgramThatEndedOnItsOwnLetsTheBatchGoOn() {
        calls.run(List.of(program("p"), tool("after")), sink);
        pending.remove("p").accept(SerialCalls.Settled.of("{\"success\":true}"));
        assertEquals(List.of("p", "after"), dispatched);
    }

    @Test
    void cuttingOffTellsTheServerToStopTheProgramAtOnceAndAbandonsTheBatch() {
        calls.run(List.of(program("p"), tool("b")), sink);
        Consumer<SerialCalls.Settled> late = pending.get("p");

        assertEquals(List.of("p", "b"), calls.cancel(true));
        assertEquals(List.of("cut off p stopBody=true"), told);
        late.accept(SerialCalls.Settled.of("{\"success\":true}"));
        assertTrue(results.isEmpty(), "放弃之后回来的结果无处可报");
        assertFalse(calls.holds("p"));
        assertEquals(0, settles);
    }

    @Test
    void cancellingAToolDoesNotTellTheServerAnything() {
        calls.run(List.of(tool("a"), tool("b")), sink);
        assertEquals(List.of("a", "b"), calls.cancel(false));
        assertTrue(told.isEmpty());
    }

    @Test
    void aBatchHandedOverWhileSettlingIsRunToo() {
        SerialCalls atOnce = new SerialCalls(new SerialCalls.Port() {
            @Override
            public void invoke(LlmToolCall call, Consumer<SerialCalls.Settled> done) {
                done.accept(SerialCalls.Settled.of("{\"success\":true}"));
            }

            @Override
            public boolean isProgram(LlmToolCall call) {
                return false;
            }

            @Override
            public void interrupt(LlmToolCall program, String why) {
            }

            @Override
            public void cutOff(LlmToolCall program, boolean stopBody) {
            }
        });
        List<LlmToolCall> second = List.of(tool("second"));
        atOnce.run(List.of(tool("first")), new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                results.put(call.id(), resultJson);
            }

            @Override
            public void settled() {
                settles++;
                if (settles == 1) {
                    atOnce.run(second, sink);   // 模型当场又回了一批
                }
            }
        });
        assertTrue(results.containsKey("second"), "结算时当场收下的下一批照样执行");
        assertEquals(2, settles);
    }

    @Test
    void synchronousResultsDoNotRecurse() {
        SerialCalls atOnce = new SerialCalls(new SerialCalls.Port() {
            @Override
            public void invoke(LlmToolCall call, Consumer<SerialCalls.Settled> done) {
                done.accept(SerialCalls.Settled.of("{\"success\":true}"));
            }

            @Override
            public boolean isProgram(LlmToolCall call) {
                return false;
            }

            @Override
            public void interrupt(LlmToolCall program, String why) {
            }

            @Override
            public void cutOff(LlmToolCall program, boolean stopBody) {
            }
        });
        List<LlmToolCall> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(tool("c" + i));
        }
        atOnce.run(many, sink);
        assertEquals(20_000, results.size());
        assertEquals(1, settles);
    }
}
