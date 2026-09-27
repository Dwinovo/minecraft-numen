package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;
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
 * 一轮里的调用按顺序执行:一个做完才派下一个,后台身体活等它收尾;等的时候来了急件,余下的逐条回"没执行"。
 *
 * <p>假的执行口把派出去的调用停在 {@link #pending} 里等测试替它回结果。结果写成 {@code running tN} 表示受理了一件会自己
 * 收尾的后台活 tN;队列里 task_finished 的正文就是收尾的那件的编号。两个读法都是测试自己的约定,真的写法在 api
 * ({@code TaskDispatch}、{@code NumenEvents})。
 */
class SerialCallsTest {

    private final Map<String, Consumer<String>> pending = new LinkedHashMap<>();
    private final List<String> dispatched = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private int settles;

    private final SerialCalls calls = new SerialCalls(
            (call, done) -> {
                dispatched.add(call.id());
                pending.put(call.id(), done);
            },
            result -> result.startsWith("running ") ? result.substring("running ".length()) : null,
            entry -> EventTypes.TASK_FINISHED.equals(entry.type()) ? entry.text() : null);

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
    };

    private static LlmToolCall call(String id) {
        return new LlmToolCall(id, "command", "{}");
    }

    private void answer(String id, String result) {
        pending.remove(id).accept(result);
    }

    private static EventQueue.Entry finished(String taskId) {
        return new EventQueue.Entry(EventTypes.TASK_FINISHED, taskId, 0, true);
    }

    private static EventQueue.Entry ownerWords(String words) {
        return new EventQueue.Entry(EventTypes.QUERY, "<query>" + words + "</query>", 0, false);
    }

    @Test
    void theNextCallGoesOutOnlyWhenTheLastOneHasItsResult() {
        calls.run(List.of(call("a"), call("b")), sink);
        assertEquals(List.of("a"), dispatched);

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
        assertEquals(0, settles);

        answer("b", "{\"success\":true}");
        assertEquals(1, settles);
        assertEquals(List.of("a", "b"), List.copyOf(results.keySet()));
    }

    @Test
    void aBackgroundJobHoldsTheRestUntilItsOwnEndArrives() {
        calls.run(List.of(call("a"), call("b")), sink);
        answer("a", "running t3");
        assertEquals("running t3", results.get("a"), "受理回执照常是 a 的结果");
        assertEquals(List.of("a"), dispatched, "t3 还没做完,b 不派");

        calls.arrived(finished("t2"), false);
        calls.arrived(new EventQueue.Entry(EventTypes.REFLEX, "<event>换了口气</event>", 0, false), false);
        assertEquals(List.of("a"), dispatched, "别的活收尾、不急的事都不算");

        calls.arrived(finished("t3"), true);
        assertEquals(List.of("a", "b"), dispatched, "t3 做完了才派 b");
        answer("b", "{\"success\":true}");
        assertEquals(1, settles);
    }

    @Test
    void aJobThatNeverEndsDoesNotHoldAnything() {
        calls.run(List.of(call("follow"), call("look")), sink);
        answer("follow", "{\"success\":true,\"data\":{\"standing\":true}}");
        assertEquals(List.of("follow", "look"), dispatched, "常驻的活没有收尾,不等");
    }

    @Test
    void theOwnerSpeakingWhileWaitingLeavesTheRestUnrunAndSaysWhy() {
        calls.run(List.of(call("a"), call("b"), call("c")), sink);
        answer("a", "running t3");

        calls.arrived(ownerWords("先停一下"), true);

        assertEquals(List.of("a"), dispatched, "余下的一个都没派");
        assertEquals(1, settles, "这一批结算,模型下一次调用读到主人的话");
        String why = ToolOutcome.failure("Not run: while you were waiting for t3 to finish, your owner spoke. "
                + "t3 keeps running; read it, then decide what to do next.");
        assertEquals(why, results.get("b"));
        assertEquals(why, results.get("c"));
        assertEquals(3, results.size(), "每个调用恰好一个结果");
    }

    @Test
    void anUrgentEventWhileWaitingNamesItsKind() {
        calls.run(List.of(call("a"), call("b")), sink);
        answer("a", "running t3");

        calls.arrived(new EventQueue.Entry(EventTypes.OWNER_HURT, "<event>主人危险</event>", 0, true), true);

        assertTrue(ToolOutcome.failed(results.get("b")));
        assertTrue(results.get("b").contains("an urgent " + EventTypes.OWNER_HURT + " event arrived"), results.get("b"));
        assertEquals(1, settles);
    }

    @Test
    void inputWhileAToolIsStillRunningDropsNothing() {
        calls.run(List.of(call("a"), call("b")), sink);
        calls.arrived(ownerWords("先停一下"), true);
        assertEquals(List.of("a"), dispatched);
        assertTrue(results.isEmpty(), "工具本身有界短:等它的结果,输入跟下一次调用走");

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
    }

    /** 后面没有调用在等它:这一批当场结算,活在后台做,她照常说话、想事。 */
    @Test
    void aJobAtTheEndOfTheReplyLeavesHerFreeAtOnce() {
        calls.run(List.of(call("a")), sink);
        answer("a", "running t3");
        assertEquals(1, settles);

        calls.arrived(ownerWords("挖得怎么样了"), true);
        assertEquals(1, results.size(), "没有在等,输入不碰任何调用");
    }

    @Test
    void cancellingReturnsWhatHadNoResultAndLateResultsAreDropped() {
        calls.run(List.of(call("a"), call("b")), sink);
        Consumer<String> late = pending.get("a");

        assertEquals(List.of("a", "b"), calls.cancel());
        late.accept("{\"success\":true}");
        assertTrue(results.isEmpty(), "放弃之后回来的结果无处可报");
        assertFalse(calls.holds("a"));
        assertEquals(0, settles);
    }

    @Test
    void synchronousResultsDoNotRecurse() {
        SerialCalls atOnce = new SerialCalls((call, done) -> done.accept("{\"success\":true}"),
                result -> null, entry -> null);
        List<LlmToolCall> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(call("c" + i));
        }
        atOnce.run(many, sink);
        assertEquals(20_000, results.size());
        assertEquals(1, settles);
    }

    @Test
    void aBatchHandedOverWhileSettlingIsRunToo() {
        SerialCalls atOnce = new SerialCalls((call, done) -> done.accept("{\"success\":true}"),
                result -> null, entry -> null);
        List<LlmToolCall> second = List.of(call("second"));
        atOnce.run(List.of(call("first")), new ToolPort.Sink() {
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
}
