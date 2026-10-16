package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.ModelSpec;
import com.dwinovo.numen.api.agent.provider.RequestOptions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 4 版的记录模型:id 与 parentId、整理是指针、换模型 / 换档位是单独的记录、插件的自定义记录;
 * 以及第 3 版的真实文件升上来之后,两个视图和升级前一样。
 */
class ConvoLogRecordsTest {

    private static final ConvoState.Msg.User ASK = new ConvoState.Msg.User("<query>去挖点铁</query>");
    private static final ConvoState.Msg CALL = new ConvoState.Msg.Assistant(new AssistantTurn("",
            List.of(new LlmToolCall("c1", "goto", "{}")), null, "", "chat/deepseek/v3"));
    private static final ConvoState.Msg RESULT = new ConvoState.Msg.Tool("c1", "{\"success\":true}");
    private static final ConvoState.Msg ARRIVED = new ConvoState.Msg.Assistant(new AssistantTurn("到了", List.of(), null));
    private static final ConvoState.Msg.Halt STOP = new ConvoState.Msg.Halt("主人按了停止", "halt.owner", List.of("a"));
    private static final ConvoState.Msg.User AGAIN = new ConvoState.Msg.User("<query>继续</query>");
    private static final ConvoState.Msg REPLY = new ConvoState.Msg.Assistant(new AssistantTurn("好的", List.of(), null));

    private static List<JsonObject> records(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
    }

    // ---- 新写的记录 ----

    @Test
    void everyRecordHasAUniqueIdAndPointsAtThePreviousOne(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        log.append(CALL, null);
        log.append(RESULT, null);
        log.appendClearBoundary();
        log.appendPersonaDivider();

        List<JsonObject> written = records(log.file());
        assertEquals(ConvoLog.FORMAT_VERSION, written.get(0).get("v").getAsInt());
        Set<String> ids = new HashSet<>();
        String previous = null;
        for (JsonObject r : written.subList(1, written.size())) {
            assertTrue(ids.add(r.get("id").getAsString()));
            assertEquals(previous, r.get("parentId").isJsonNull() ? null : r.get("parentId").getAsString());
            previous = r.get("id").getAsString();
        }
        assertEquals("message", written.get(1).get("type").getAsString());
    }

    @Test
    void aCompactionPointsAtTheKeptMessagesInsteadOfCopyingThem(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        log.append(CALL, null);
        log.append(RESULT, null);
        log.append(ARRIVED, null);
        log.append(AGAIN, null);
        log.appendCompactSummary("[摘要] 去过矿洞", 2, new JsonObject());
        log.append(REPLY, null);

        String text = Files.readString(log.file(), StandardCharsets.UTF_8);
        assertEquals(1, text.split("<query>继续</query>", -1).length - 1, "保留的那条在文件里只出现一次");
        assertFalse(text.contains("preserved"));
        List<JsonObject> written = records(log.file());
        JsonObject compact = written.get(6);
        assertEquals("compact", compact.get("type").getAsString());
        assertEquals(written.get(4).get("id"), compact.get("firstKeptId"), "指针指向保留的第一条");

        ConvoLog reopened = ConvoLog.atFile(log.file());
        assertEquals(List.of(new ConvoState.Msg.User("[摘要] 去过矿洞"), ARRIVED, AGAIN, REPLY), reopened.load(100));
        assertEquals(List.of(ASK, CALL, RESULT, ARRIVED, AGAIN,
                new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER), REPLY), reopened.loadDisplay(100));
    }

    @Test
    void aSecondCompactionCanKeepMessagesFromBeforeTheFirst(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        log.append(ARRIVED, null);
        log.append(AGAIN, null);
        log.appendCompactSummary("[摘要一]", 2, null);
        log.append(REPLY, null);
        log.appendCompactSummary("[摘要二]", 3, null);   // 保留 ARRIVED、AGAIN、REPLY:前两条在第一次整理之前

        assertEquals(List.of(new ConvoState.Msg.User("[摘要二]"), ARRIVED, AGAIN, REPLY),
                ConvoLog.atFile(log.file()).load(100));
    }

    @Test
    void keepingMoreThanTheContextHoldsIsRefused(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        assertThrows(IllegalArgumentException.class, () -> log.appendCompactSummary("[摘要]", 2, null));
    }

    @Test
    void aChangeOfModelOrThinkingIsItsOwnRecordAndOnlyWhenItChanged(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        log.noteEndpoint(endpoint("deepseek", "v3", ThinkingLevel.AUTO));
        log.noteEndpoint(endpoint("deepseek", "v3", ThinkingLevel.AUTO));
        log.append(ARRIVED, null);
        log.noteEndpoint(endpoint("deepseek", "r1", ThinkingLevel.HIGH));

        List<String> types = records(log.file()).stream().skip(1).map(r -> r.get("type").getAsString()).toList();
        assertEquals(List.of("message", "model-change", "thinking-change", "message", "model-change", "thinking-change"),
                types);
        JsonObject last = records(log.file()).get(6);
        assertEquals("high", last.get("level").getAsString());

        // 重新打开之后接着比:同一个端点不再记
        int before = records(log.file()).size();
        ConvoLog reopened = ConvoLog.atFile(log.file());
        reopened.noteEndpoint(endpoint("deepseek", "r1", ThinkingLevel.HIGH));
        assertEquals(before, records(log.file()).size(), "记录条数没有变");
        assertEquals(List.of(ASK, ARRIVED), reopened.load(100), "换模型的记录不进模型上下文");
        assertEquals(List.of(ASK, ARRIVED), reopened.loadDisplay(100));
    }

    private static LlmEndpoint endpoint(String provider, String model, ThinkingLevel level) {
        return new LlmEndpoint(provider, "http://x", Map.of(), "k", "",
                new ModelSpec(model, ThinkingTable.AUTO_ONLY, true), 0,
                LlmEndpoint.unresolved("").compat(), new RequestOptions(null, 0, level));
    }

    @Test
    void aPluginRecordIsKeptOutOfBothViewsAndComesBackToThePlugin(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(ASK, null);
        JsonObject data = new JsonObject();
        data.addProperty("mood", "好");
        log.appendCustom("my_mod:mood", data);
        log.appendCustom("other_mod:thing", new JsonObject());
        log.append(ARRIVED, null);

        ConvoLog reopened = ConvoLog.atFile(log.file());
        assertEquals(List.of(ASK, ARRIVED), reopened.load(100));
        assertEquals(List.of(ASK, ARRIVED), reopened.loadDisplay(100));
        List<ConvoLog.Custom> mine = reopened.custom("my_mod:mood");
        assertEquals(1, mine.size());
        assertEquals("好", mine.get(0).data().get("mood").getAsString());
        assertThrows(IllegalArgumentException.class, () -> log.appendCustom("没有命名空间", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> log.appendCustom("message", new JsonObject()));
    }

    // ---- 第 3 版的真实文件 ----

    /** 第 3 版的写法逐字:消息按 role 分、无 type/id;整理把保留的两条抄了一份;人设分隔的 id 是人设库的条目 id。 */
    private static final String V3 = String.join("\n",
            "{\"type\":\"header\",\"v\":3,\"created\":1000}",
            "{\"role\":\"user\",\"content\":\"<query>去挖点铁</query>\",\"ts\":1001}",
            "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"name\":\"goto\",\"arguments\":\"{}\"}],\"origin\":\"chat/deepseek/v3\",\"ts\":1002}",
            "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"{\\\"success\\\":true}\",\"ts\":1003}",
            "{\"role\":\"assistant\",\"content\":\"到了\",\"ts\":1004}",
            "{\"type\":\"halt\",\"reason\":\"主人按了停止\",\"code\":\"halt.owner\",\"args\":[\"a\"],\"ts\":1005}",
            "{\"role\":\"user\",\"content\":\"<query>继续</query>\",\"ts\":1006,\"conv\":\"G\"}",
            "{\"type\":\"compact\",\"content\":\"[摘要] 去过矿洞\","
                    + "\"preserved\":[{\"type\":\"halt\",\"reason\":\"主人按了停止\",\"code\":\"halt.owner\",\"args\":[\"a\"]},"
                    + "{\"role\":\"user\",\"content\":\"<query>继续</query>\"}],"
                    + "\"meta\":{\"trigger\":\"auto\",\"droppedMessages\":4},\"ts\":1007}",
            "{\"role\":\"assistant\",\"content\":\"好的\",\"ts\":1008}",
            "{\"type\":\"persona-change\",\"id\":\"猫娘\",\"ts\":1009}",
            "{\"type\":\"goal\",\"x\":1,\"ts\":1010}") + "\n";

    private static final List<ConvoState.Msg> V3_MODEL_VIEW = List.of(
            new ConvoState.Msg.User("[摘要] 去过矿洞"), STOP, AGAIN, REPLY);
    private static final List<ConvoState.Msg> V3_PANEL_VIEW = List.of(
            ASK, CALL, RESULT, ARRIVED, STOP, AGAIN, new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER), REPLY,
            new ConvoState.Msg.User(ConvoLog.PERSONA_DIVIDER));

    @Test
    void aRealV3FileKeepsBothViewsAndLosesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chat.jsonl");
        Files.writeString(file, V3, StandardCharsets.UTF_8);
        ConvoLog log = ConvoLog.atFile(file);

        assertEquals(V3_MODEL_VIEW, log.load(100), "给模型的上下文和升级前一样");
        assertEquals(V3_PANEL_VIEW, log.loadDisplay(100), "给面板的视图和升级前一样");

        assertEquals(V3, Files.readString(dir.resolve("chat.jsonl.v3.bak"), StandardCharsets.UTF_8), "升级前的原文留了备份");
        List<JsonObject> written = records(file);
        assertEquals(4, written.get(0).get("v").getAsInt());
        assertEquals(10, written.size() - 1, "记录一条不多一条不少(原来 10 条,抄的两条换成了指针)");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, text.split("<query>继续</query>", -1).length - 1, "保留的那条不再抄第二份");
        assertFalse(text.contains("preserved"));

        JsonObject compact = written.get(7);
        assertEquals("compact", compact.get("type").getAsString());
        assertEquals(written.get(5).get("id"), compact.get("firstKeptId"), "指针指向 halt 那条原记录");
        assertEquals("auto", compact.getAsJsonObject("meta").get("trigger").getAsString(), "记账信息还在");
        assertEquals("G", written.get(6).get("conv").getAsString(), "会话印还在");
        assertEquals("猫娘", written.get(9).get("personaId").getAsString());
        assertEquals("goal", written.get(10).get("type").getAsString(), "不认得的类型原样带过");
        assertEquals("猫娘", log.legacyPersonaId());
    }

    @Test
    void aV3CopyThatDoesNotMatchTheTailIsRestoredNotLost(@TempDir Path dir) throws IOException {
        // 抄的那条和整理那一刻上下文里的任何一条都对不上(旧版本改写过):不能丢,补回成只给模型的原文副本
        Path file = dir.resolve("chat.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"header\",\"v\":3,\"created\":1000}",
                "{\"role\":\"user\",\"content\":\"<query>去挖点铁</query>\",\"ts\":1001}",
                "{\"role\":\"assistant\",\"content\":\"到了\",\"ts\":1002}",
                "{\"type\":\"compact\",\"content\":\"[摘要]\","
                        + "\"preserved\":[{\"role\":\"assistant\",\"content\":\"到了(改写过的)\"}],\"ts\":1003}",
                "{\"role\":\"user\",\"content\":\"<query>继续</query>\",\"ts\":1004}") + "\n", StandardCharsets.UTF_8);
        ConvoLog log = ConvoLog.atFile(file);

        assertEquals(List.of(new ConvoState.Msg.User("[摘要]"),
                        new ConvoState.Msg.Assistant(new AssistantTurn("到了(改写过的)", List.of(), null)), AGAIN),
                log.load(100), "保留的原文还在模型上下文里");
        assertEquals(List.of(ASK, new ConvoState.Msg.Assistant(new AssistantTurn("到了", List.of(), null)),
                        new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER), AGAIN),
                log.loadDisplay(100), "面板不多显示一遍");
        assertNotNull(records(file).get(3).get("restored"));
    }
}
