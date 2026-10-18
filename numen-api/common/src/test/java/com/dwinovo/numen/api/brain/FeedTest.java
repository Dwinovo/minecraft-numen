package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 线上的样子读回来和写出去的一样;读不懂的一条跳过,不拖垮整包。 */
class FeedTest {

    private static final CompanionState STATE = new CompanionState(Phase.MODEL, Hold.BLOCKED,
            Cause.of(Cause.NO_KEY, "我的档案"), true, new LoopStatus.Doing("numen.activity.dig", List.of("64")), 0.5,
            List.of(new EventQueue.Preview("a1b2", "query", "<query>去挖矿</query>")), "numen.loop.compacting", null,
            goal(), 1200, new Usage(1, 2, 3, 4), new Usage(5, 6, 7, 8), 9000, 2, 41, 128000, "p1", "prov1", "v1", "s1", "G", List.of("call_1", "call_2"), 17);

    private static JsonObject goal() {
        JsonObject o = new JsonObject();
        o.addProperty("objective", "挖 64 个铁");
        return o;
    }

    @Test
    void theWholeStateSurvivesTheWire() {
        assertEquals(STATE, CompanionState.fromJson(JsonParser.parseString(STATE.toJson().toString()).getAsJsonObject()));
    }

    @Test
    void anIdleCompanionWithNothingBoundSurvivesToo() {
        CompanionState idle = new CompanionState(null, null, null, false, null, 0.0, List.of(), null, null, null, 0,
                Usage.ZERO, Usage.ZERO, 0, 0, 0, 0, null, null, null, null, null, List.of(), 0);

        assertEquals(idle, CompanionState.fromJson(JsonParser.parseString(idle.toJson().toString()).getAsJsonObject()));
    }

    @Test
    void everyEventSurvivesTheWireInOrder() {
        ConvoLog.Line line = new ConvoLog.Line("c0ffee01", 1234, "G", new ConvoState.Msg.Assistant(
                new AssistantTurn("好", List.of(new LlmToolCall("t1", "lua", "{\"code\":\"x\"}")), null, "想一想")));
        ConvoLog.Line cut = new ConvoLog.Line("c0ffee02", 1300, null, new ConvoState.Msg.Halt("主人按了停止", null, List.of()));
        List<Feed> events = List.of(new Feed.TurnStart(true), new Feed.Delta("你", "想"), new Feed.Replied("你好", true),
                new Feed.TurnCut(), new Feed.Halted(), new Feed.Failed(Cause.of(Cause.NETWORK)),
                new Feed.Blocked(Cause.of(Cause.UNBOUND)), new Feed.Wiped(), new Feed.Line(line), new Feed.Line(cut),
                new Feed.Pending(List.of(new EventQueue.Preview("x", "compact", "numen.loop.compact"))),
                new Feed.Status(STATE));

        List<Feed> back = Feed.listFromJson(JsonParser.parseString(Feed.toJson(events).toString()).getAsJsonArray());

        assertEquals(events, back);
    }

    @Test
    void anEventKindFromANewerServerIsSkippedAndTheRestStillRead() {
        var array = new com.google.gson.JsonArray();
        array.add(new Feed.Halted().toJson());
        JsonObject unknown = new JsonObject();
        unknown.addProperty("kind", "from_the_future");
        array.add(unknown);
        array.add(new Feed.TurnCut().toJson());

        assertEquals(List.of(new Feed.Halted(), new Feed.TurnCut()), Feed.listFromJson(array));
    }
}
