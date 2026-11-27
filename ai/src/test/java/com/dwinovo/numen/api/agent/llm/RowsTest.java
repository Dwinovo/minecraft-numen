package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.provider.AnthropicProvider;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对话记录的行:写成 Claude Code 会话记录的样子,读回来是同一段上下文;面板与模型各读各的视图。 */
class RowsTest {

    private static final Rows.Stamp NONE = Rows.Stamp.NONE;
    private static final ConvoState.Msg USER = new ConvoState.Msg.User("<query>去挖点铁</query>");
    private static final ConvoState.Msg CALL = new ConvoState.Msg.Assistant(new AssistantTurn("好的",
            List.of(new LlmToolCall("c1", "goto", "{\"x\":1}"), new LlmToolCall("c2", "dig", "{}")), null));
    private static final ConvoState.Msg RESULT = new ConvoState.Msg.Tool("c1", "{\"success\":true}");
    private static final ConvoState.Msg REPLY = new ConvoState.Msg.Assistant(new AssistantTurn("挖完了", List.of(), null));

    /** 把消息按顺序写成行(一条消息可能是好几行)。 */
    private static List<JsonObject> write(Rows.Writer writer, ConvoState.Msg... messages) {
        List<JsonObject> rows = new ArrayList<>();
        for (ConvoState.Msg msg : messages) {
            rows.addAll(writer.write(msg, NONE));
        }
        return rows;
    }

    private static List<ConvoState.Msg> context(List<JsonObject> rows) {
        Rows.Opening opening = Rows.open(rows);
        return Rows.replay(opening.rows(), opening.last()).context();
    }

    private static List<ConvoState.Msg> shown(List<JsonObject> rows) {
        return Rows.lines(rows).stream().map(Rows.Line::msg).toList();
    }

    @Test
    void theRecordIsClaudeCodesTranscriptAndReadsBackAsTheSameContext() {
        Rows.Writer writer = new Rows.Writer("1.0");
        List<JsonObject> rows = write(writer, USER, CALL, RESULT, REPLY);

        assertEquals(List.of(USER, CALL, RESULT, REPLY), context(rows));
        assertEquals(List.of("user", "assistant", "assistant", "assistant", "user", "assistant"),
                rows.stream().map(r -> r.get("type").getAsString()).toList(), "one row per assistant block");
        assertEquals("text", rows.get(1).getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", rows.get(2).getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject().get("type").getAsString());
        assertEquals(rows.get(1).getAsJsonObject("message").get("id"), rows.get(2).getAsJsonObject("message").get("id"),
                "the blocks of one reply share message.id");
        assertEquals(rows.get(0).get("uuid"), rows.get(1).get("parentUuid"), "rows form a chain");
    }

    @Test
    void aToolResultPointsAtTheRowThatCalledIt() {
        List<JsonObject> rows = write(new Rows.Writer("1.0"), USER, CALL, RESULT);

        assertEquals("c1", rows.get(4).getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject()
                .get("tool_use_id").getAsString());
        assertEquals(rows.get(2).get("uuid").getAsString(), rows.get(4).get("sourceToolAssistantUUID").getAsString());
    }

    @Test
    void thinkingTheSignatureAndTheOriginSurviveTheRoundTrip() {
        JsonObject extras = new JsonObject();
        extras.addProperty(AnthropicProvider.SIGNATURE_KEY, "sig-1");
        extras.addProperty("other", "kept");
        ConvoState.Msg thought = new ConvoState.Msg.Assistant(new AssistantTurn("先去矿洞", List.of(), extras,
                "背包里没镐", "anthropic/vendor/some/model"));
        List<JsonObject> rows = write(new Rows.Writer("1.0"), USER, thought);

        assertEquals(List.of(USER, thought), context(rows));
        JsonObject block = rows.get(1).getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("thinking", block.get("type").getAsString());
        assertEquals("sig-1", block.get("signature").getAsString(), "the signature is the thinking block's own");
        assertEquals("some/model", rows.get(1).getAsJsonObject("message").get("model").getAsString());
    }

    @Test
    void anOwnerStopIsTheInterruptedRowAndAnyOtherCutIsAnApiErrorRowWithItsStructuredCause() {
        ConvoState.Msg stop = new ConvoState.Msg.Halt(Rows.OWNER_STOP_WORDS, Rows.OWNER_STOP_CODE, List.of());
        ConvoState.Msg failed = new ConvoState.Msg.Halt("this turn did not reach the model", "llm.unauthorized",
                List.of("401", "api.example.com"));
        ConvoState.Msg plain = new ConvoState.Msg.Halt("old cut");
        List<JsonObject> rows = write(new Rows.Writer("1.0"), USER, stop, failed, plain);

        assertEquals(Rows.INTERRUPTED, rows.get(1).getAsJsonObject("message").get("content").getAsString());
        assertTrue(rows.get(2).get("isApiErrorMessage").getAsBoolean());
        assertEquals("llm.unauthorized", rows.get(2).get("code").getAsString());
        assertEquals(List.of(USER, stop, failed, plain), context(rows));
        assertEquals(List.of(USER, stop, failed, plain), shown(rows), "the panel draws the cuts as they were");
    }

    @Test
    void metaTextIsReadByTheModelButNotDrawnByThePanel() {
        ConvoState.Msg meta = new ConvoState.Msg.User("Stop hook feedback:\n[c]: more", true);
        List<JsonObject> rows = write(new Rows.Writer("1.0"), USER, meta, REPLY);

        assertEquals(List.of(USER, meta, REPLY), context(rows));
        assertEquals(List.of(USER, REPLY), shown(rows));
        assertTrue(rows.get(1).get("isMeta").getAsBoolean());
    }

    @Test
    void aCompactionKeepsTheTailVerbatimAndOpeningReplaysSummaryTailThenWhatCameAfter() {
        Rows.Writer writer = new Rows.Writer("1.0");
        ConvoState.Msg old = new ConvoState.Msg.User("<query>很久以前</query>");
        ConvoState.Msg oldReply = new ConvoState.Msg.Assistant(new AssistantTurn("嗯", List.of(), null));
        List<JsonObject> rows = write(writer, old, oldReply, USER, REPLY);
        rows.addAll(writer.compact("[摘要]", 2, new JsonObject(), NONE));
        ConvoState.Msg later = new ConvoState.Msg.User("<query>后来</query>");
        rows.addAll(writer.write(later, NONE));

        Rows.Opening opening = Rows.open(rows);

        assertEquals(List.of(new ConvoState.Msg.User("[摘要]"), USER, REPLY, later),
                Rows.replay(opening.rows(), opening.last()).context(), "summary, the kept tail, then the new rows");
        assertEquals(rows.getLast().get("uuid").getAsString(), opening.last());
        assertEquals(List.of(old, oldReply, USER, REPLY, new ConvoState.Msg.User(Rows.COMPACT_DIVIDER), later),
                shown(rows), "the panel sees every row once, the compaction as a divider");
        JsonObject boundary = rows.get(4);
        assertEquals("compact_boundary", boundary.get("subtype").getAsString());
        assertTrue(boundary.get("parentUuid").isJsonNull());
        assertEquals(rows.get(3).get("uuid"), boundary.get("logicalParentUuid"));
    }

    @Test
    void aWriterThatAdoptsAReplayContinuesTheChainAndKnowsWhatItCanKeep() {
        List<JsonObject> rows = write(new Rows.Writer("1.0"), USER, CALL, RESULT);
        Rows.Opening opening = Rows.open(rows);

        Rows.Writer second = new Rows.Writer("1.0");
        second.adopt(Rows.replay(opening.rows(), opening.last()));
        List<JsonObject> more = second.write(REPLY, NONE);

        assertEquals(rows.getLast().get("uuid"), more.getFirst().get("parentUuid"));
        List<JsonObject> boundary = second.compact("[摘要]", 1, null, NONE);
        assertEquals(more.getFirst().get("uuid"), boundary.getFirst().getAsJsonObject("compactMetadata")
                .getAsJsonObject("preservedSegment").get("headUuid"));
    }

    @Test
    void stateRowsAreNotInTheContextAndTheLatestGoalIsTheOneStillAlive() {
        Rows.Writer writer = new Rows.Writer("1.0");
        List<JsonObject> rows = write(writer, USER);
        rows.add(writer.attach(Rows.GOAL_STATUS, goal("挖铁", false, null, true), NONE));
        rows.add(writer.attach(Rows.GOAL_STATUS, goal("挖铁", false, "还差 30 个", false), NONE));
        rows.add(writer.attach(Rows.PERSONA_CHANGED, new JsonObject(), NONE));

        Rows.Opening opening = Rows.open(rows);
        Rows.Goal goal = Rows.goal(opening.goal());

        assertEquals(List.of(USER), Rows.replay(opening.rows(), opening.last()).context());
        assertEquals("挖铁", goal.condition());
        assertEquals(2, goal.rounds());
        assertEquals("还差 30 个", goal.reason());
        assertEquals(new ConvoState.Msg.User(Rows.PERSONA_DIVIDER), Rows.lines(rows).getLast().msg());

        rows.add(writer.attach(Rows.GOAL_STATUS, goal("挖铁", true, null, true), NONE));
        assertNull(Rows.goal(Rows.open(rows).goal()), "a cleared goal is no goal");
    }

    @Test
    void theGoalOutlivesACompaction() {
        Rows.Writer writer = new Rows.Writer("1.0");
        List<JsonObject> rows = write(writer, USER, REPLY);
        rows.add(writer.attach(Rows.GOAL_STATUS, goal("挖铁", false, null, true), NONE));
        rows.addAll(writer.compact("[摘要]", 0, null, NONE));

        assertEquals("挖铁", Rows.goal(Rows.open(rows).goal()).condition());
    }

    @Test
    void aRowOfAnUnknownTypeIsSkippedAndStaysInTheFile() {
        Rows.Writer writer = new Rows.Writer("1.0");
        List<JsonObject> rows = write(writer, USER);
        JsonObject plugin = new JsonObject();
        plugin.addProperty("type", "someplugin:note");
        plugin.addProperty("uuid", "x");
        rows.add(plugin);
        rows.addAll(writer.write(REPLY, NONE));

        assertEquals(List.of(USER, REPLY), context(rows));
        assertEquals(List.of(USER, REPLY), shown(rows));
        assertEquals(3, Rows.open(rows).rows().size(), "nothing is dropped from what is handed over");
    }

    @Test
    void everyRowCarriesTheGroupAndAUserRowWhoSaidIt() {
        Rows.Writer writer = new Rows.Writer("1.0");
        ConvoState.Msg said = new ConvoState.Msg.User("<query>去挖点铁</query>", false, "player@ab12cd");
        List<JsonObject> rows = new ArrayList<>(writer.write(said, new Rows.Stamp("G")));
        rows.addAll(writer.write(REPLY, new Rows.Stamp("G")));

        assertEquals("G", rows.get(0).get("conv").getAsString());
        assertEquals("player@ab12cd", rows.get(0).get("from").getAsString());
        assertEquals("G", rows.get(1).get("conv").getAsString());
        assertFalse(rows.get(1).has("from"));
        assertEquals(said, context(rows).getFirst(), "who said it comes back with the words");
        assertEquals("G", Rows.lines(rows).getFirst().conv());
        assertTrue(Rows.lines(rows).getFirst().ts() > 0);
    }

    private static JsonObject goal(String condition, boolean met, String reason, boolean sentinel) {
        JsonObject o = new JsonObject();
        o.addProperty("condition", condition);
        o.addProperty("met", met);
        if (reason != null) {
            o.addProperty("reason", reason);
        }
        if (sentinel) {
            o.addProperty("sentinel", true);
        }
        return o;
    }
}
