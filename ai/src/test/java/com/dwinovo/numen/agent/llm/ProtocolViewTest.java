package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 历史发给模型前的三条规则。
 *
 * <p>历史如实记录发生过什么(包括切断点),合法序列只在 {@link ProtocolView#forWire} 现算。每条规则
 * 各测一遍,再拿一个协议校验器把所有形状过一遍——服务商拒的正是这三种:没结果的调用、
 * 发出去的 Halt、连着的 user。
 */
class ProtocolViewTest {

    private static ConvoState.Msg user(String s) {
        return new ConvoState.Msg.User(s);
    }

    private static ConvoState.Msg calls(String... ids) {
        List<LlmToolCall> list = new ArrayList<>();
        for (String id : ids) list.add(new LlmToolCall(id, "goto", "{}"));
        return new ConvoState.Msg.Assistant(new AssistantTurn("", list, null));
    }

    private static ConvoState.Msg reply(String s) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(s, List.of(), null));
    }

    private static ConvoState.Msg result(String id) {
        return new ConvoState.Msg.Tool(id, "{\"success\":true}");
    }

    private static ConvoState.Msg halt(String reason) {
        return new ConvoState.Msg.Halt(reason);
    }

    private static JsonObject json(ConvoState.Msg msg) {
        return JsonParser.parseString(assertInstanceOf(ConvoState.Msg.Tool.class, msg).content())
                .getAsJsonObject();
    }

    // ---- 规则一:悬空调用补结果 ----

    @Test
    void danglingCallsTakeTheReasonOfTheHaltRightAfterThem() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(
                user("去挖铁"), calls("c1", "c2"), result("c1"), halt("被主人打断"), user("回来")));

        assertEquals(5, wire.size());
        assertEquals("c1", ((ConvoState.Msg.Tool) wire.get(2)).toolCallId(), "真结果原样在前");
        ConvoState.Msg.Tool filled = assertInstanceOf(ConvoState.Msg.Tool.class, wire.get(3));
        assertEquals("c2", filled.toolCallId());
        assertFalse(json(filled).get("success").getAsBoolean());
        assertEquals("被主人打断", json(filled).get("message").getAsString());
        assertEquals("回来", ((ConvoState.Msg.User) wire.get(4)).content(),
                "原因已经写进补的结果,user 不再挂切断说明");
    }

    @Test
    void danglingCallsWithoutAHaltWereCutByClosingTheGame() {
        List<ConvoState.Msg> atEnd = ProtocolView.forWire(List.of(user("去挖铁"), calls("c1")));
        assertEquals(3, atEnd.size());
        assertEquals(ProtocolView.CLOSED_BEFORE_RESULT, json(atEnd.get(2)).get("message").getAsString());

        List<ConvoState.Msg> beforeUser = ProtocolView.forWire(List.of(calls("c1"), user("在吗")));
        assertEquals("c1", ((ConvoState.Msg.Tool) beforeUser.get(1)).toolCallId());
        assertEquals(ProtocolView.CLOSED_BEFORE_RESULT, json(beforeUser.get(1)).get("message").getAsString());
        assertInstanceOf(ConvoState.Msg.User.class, beforeUser.get(2));

        List<ConvoState.Msg> beforeAssistant = ProtocolView.forWire(List.of(calls("c1"), reply("好")));
        assertEquals(ProtocolView.CLOSED_BEFORE_RESULT, json(beforeAssistant.get(1)).get("message").getAsString());
    }

    @Test
    void filledResultsFollowTheModelsCallOrder() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(calls("c1", "c2", "c3"), result("c2"), halt("死了")));

        List<String> ids = new ArrayList<>();
        for (ConvoState.Msg m : wire) {
            if (m instanceof ConvoState.Msg.Tool t) ids.add(t.toolCallId());
        }
        assertEquals(List.of("c2", "c1", "c3"), ids);
    }

    // ---- 规则二:Halt 不发,切断回复时给下一条 user 加说明 ----

    @Test
    void aHaltThatCutAReplyIsExplainedAtTheStartOfTheNextUserMessage() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(
                user("去挖铁"), calls("c1"), result("c1"), halt("被主人打断"), user("<query>算了</query>")));

        assertEquals(4, wire.size(), "Halt 本身不发");
        assertEquals("（上一轮被打断：被主人打断）\n<query>算了</query>",
                ((ConvoState.Msg.User) wire.get(3)).content());
    }

    @Test
    void theExplanationIsDroppedOnceTheModelHasSpokenAgain() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(
                user("去挖铁"), calls("c1"), result("c1"), halt("主人断线了"), reply("我接着说"), user("好")));

        assertEquals("好", ((ConvoState.Msg.User) wire.get(wire.size() - 1)).content());
    }

    @Test
    void aHaltWithNothingAfterItIsSimplyNotSent() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(user("去挖铁"), halt("被主人打断")));
        assertEquals(List.of(user("去挖铁")), wire);
    }

    // ---- 规则三:相邻 user 合并 ----

    @Test
    void adjacentUserMessagesAreMergedWithABlankLine() {
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(
                user("<query>在吗</query>"), user("<runtime_state/>"), reply("在"), user("a"), user("b")));

        assertEquals(List.of(user("<query>在吗</query>\n\n<runtime_state/>"), reply("在"), user("a\n\nb")), wire);
    }

    @Test
    void aCutReplyFollowedByAnotherUserStillMergesIntoOne() {
        // 请求发出去模型还没回话就被打断,主人又说了一句:两句之间夹着说明,但仍是一条 user
        List<ConvoState.Msg> wire = ProtocolView.forWire(List.of(
                user("去挖铁"), halt("被主人打断"), user("算了回来")));

        assertEquals(List.of(user("去挖铁\n\n（上一轮被打断：被主人打断）\n算了回来")), wire);
    }

    // ---- 正常序列 ----

    @Test
    void aWellFormedHistoryPassesThroughUnchanged() {
        List<ConvoState.Msg> history = List.of(
                user("去挖铁"), calls("c1", "c2"), result("c1"), result("c2"), reply("挖完了"), user("辛苦了"));

        assertEquals(history, ProtocolView.forWire(history));
    }

    @Test
    void theRecordedHistoryIsNeverTouched() {
        List<ConvoState.Msg> history = new ArrayList<>(List.of(user("a"), calls("c1"), halt("死了"), user("b")));
        List<ConvoState.Msg> copy = List.copyOf(history);

        ProtocolView.forWire(history);

        assertEquals(copy, history);
    }

    // ---- 所有形状发出去都合法 ----

    @Test
    void everyShapeTheLoopCanRecordComesOutValid() {
        List<List<ConvoState.Msg>> shapes = List.of(
                // 游戏在工具跑着的时候被关掉,重进后主人说话
                List.of(user("去挖铁"), calls("c1"), user("在吗")),
                // 请求在飞时被打断,又被打断,再说话
                List.of(user("a"), halt("被主人打断"), halt("主人断线了"), user("b")),
                // 工具跑到一半死了,复活事件进来,运行期状态单独一条
                List.of(user("a"), calls("c1", "c2"), result("c1"), halt("你死了(摔死)"),
                        user("<events>复活</events>"), user("<runtime_state/>")),
                // 失败回合没留回复,之后又来一句
                List.of(reply("好"), user("a"), user("b")),
                // 压缩请求:待总结的旧段末尾是切断点,后面接压缩指令
                List.of(user("a"), calls("c1"), halt("被主人打断"), user("请压缩以上对话")),
                // 目标评估请求:只有一条 user
                List.of(user("<objective>挖 64 铁</objective>")));

        for (List<ConvoState.Msg> shape : shapes) {
            assertValid(ProtocolView.forWire(shape), shape);
        }
    }

    /** 服务商会拒的三种东西一样都不许有。 */
    private static void assertValid(List<ConvoState.Msg> wire, List<ConvoState.Msg> source) {
        Set<String> waiting = new HashSet<>();
        ConvoState.Msg prev = null;
        for (ConvoState.Msg m : wire) {
            switch (m) {
                case ConvoState.Msg.Halt h -> fail("Halt 被发出去了:" + source);
                case ConvoState.Msg.Tool t -> assertTrue(waiting.remove(t.toolCallId()), "没对上调用的结果:" + source);
                case ConvoState.Msg.User u -> {
                    assertTrue(waiting.isEmpty(), "调用还没结果就插进了 user:" + source);
                    assertFalse(prev instanceof ConvoState.Msg.User, "相邻的 user:" + source);
                }
                case ConvoState.Msg.Assistant a -> {
                    assertTrue(waiting.isEmpty(), "调用还没结果就来了下一条 assistant:" + source);
                    for (LlmToolCall tc : a.turn().toolCalls()) waiting.add(tc.id());
                }
            }
            prev = m;
        }
        assertTrue(waiting.isEmpty(), "结尾还有没结果的调用:" + source);
    }
}
