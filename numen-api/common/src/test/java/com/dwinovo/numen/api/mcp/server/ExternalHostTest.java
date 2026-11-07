package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolCall;
import com.dwinovo.numen.api.agent.tool.ToolHints;
import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.mcp.server.McpHost;
import com.dwinovo.numen.mcp.server.McpToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外接大脑在游戏里的那一头(假的手,不走 HTTP):工具表是她的工具表加控制工具、回执与 get_events 的正文、作用域、渠道。
 * HTTP、认人、限流、审计、进度、征询的传输在 {@code mcp} 模块的 {@code McpEndpointTest}。
 */
class ExternalHostTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    /** 假的手:记下 say 说了什么;名册是固定的两只;invoke 报两次进展。 */
    private static final class Hands implements ExternalHands {
        final List<String> said = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        final List<String> heard = new ArrayList<>();
        volatile String events;

        @Override
        public CompletableFuture<String> guide(UUID companion) {
            return CompletableFuture.completedFuture("THE GUIDE of " + companion);
        }

        @Override
        public CompletableFuture<List<NumenTool>> tools() {
            return CompletableFuture.completedFuture(List.of(new Probe("zeta", ToolHints.UNKNOWN),
                    new Probe("lua", new ToolHints("Run Lua program", false, true, false, true))));
        }

        @Override
        public CompletableFuture<List<Companion>> companions() {
            return CompletableFuture.completedFuture(List.of(new Companion(ALICE, "alice"), new Companion(BOB, "bob")));
        }

        @Override
        public CompletableFuture<Boolean> create(String name) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> delete(UUID companion) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<String> takeEvents(UUID companion) {
            return CompletableFuture.completedFuture(events);
        }

        @Override
        public CompletableFuture<String> runtimeState(UUID companion) {
            return CompletableFuture.completedFuture("<runtime_state>of " + companion + "</runtime_state>");
        }

        @Override
        public ExternalWait.Parked awaitEvents(UUID companion) {
            return new ExternalWait.Parked(new CompletableFuture<>(), () -> null);
        }

        @Override
        public CompletableFuture<Boolean> say(UUID companion, String text) {
            said.add(companion + ":" + text);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> hear(UUID companion, String text, Visits.Channel channel) {
            heard.add(companion + ":" + text);
            channel.said(companion, "heard: " + text);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<ConsentDesk.Reply> answer(UUID companion, long id, ConsentAnswer.Decision decision,
                                                           String note) {
            answers.add(companion + "#" + id + ":" + decision + ":" + note);
            return CompletableFuture.completedFuture(ConsentDesk.Reply.ANSWERED);
        }

        @Override
        public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson,
                                                Consumer<String> progress) {
            progress.accept("step 1");
            progress.accept("step 2");
            return CompletableFuture.completedFuture(ToolOutcome.success("ran " + toolName + " " + argsJson));
        }
    }

    /** 她的工具表里的一个工具(只有名字和性质有用)。 */
    private record Probe(String name, ToolHints hints) implements NumenTool {
        @Override
        public String description() {
            return "probe";
        }

        @Override
        public java.util.Map<String, Object> parameterSchema() {
            return null;
        }

        @Override
        public void invoke(ToolCall call) {
            throw new UnsupportedOperationException();
        }
    }

    private final Hands hands = new Hands();
    private final McpChannels channels = new McpChannels();
    private final ExternalHost host = new ExternalHost(owner -> hands, channels, "9.9.9");

    private static McpToken token(McpToken.Role role, McpToken.Scope scope) {
        return new McpToken(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString(), OWNER, role, scope,
                "", 0, 0);
    }

    private static final McpToken BRAIN = token(McpToken.Role.BRAIN, McpToken.Scope.ALL);

    private McpHost.Result call(McpToken who, String tool, JsonObject args) throws Exception {
        return host.call(who, tool, args.toString(), 5, McpHost.Progress.NONE);
    }

    private static JsonObject args(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static List<String> names(List<McpHost.ToolInfo> tools) {
        return tools.stream().map(McpHost.ToolInfo::name).toList();
    }

    // ---- 工具表 ----

    @Test
    void aBrainSeesTheControlToolsThenHerToolBookSortedByName() throws Exception {
        List<McpHost.ToolInfo> tools = host.tools(BRAIN);

        assertEquals(List.of("list_companions", "create_companion", "delete_companion", "get_events", "guide", "say", "lua",
                        "zeta"),
                names(tools));
        McpHost.ToolInfo getEvents = tools.get(3);
        assertTrue(getEvents.hints().readOnly(), "get_events 取走的是递给她的话,不改世界");
        assertTrue(tools.get(2).hints().destructive(), "遣散是破坏性的");
        assertTrue(tools.get(4).hints().readOnly(), "guide 只读");
        McpHost.ToolInfo lua = tools.get(6);
        assertEquals("Run Lua program", lua.title());
        assertTrue(lua.hints().destructive() && lua.hints().openWorld(), "跑程序的工具按最保守的标");
        assertTrue(lua.inputSchemaJson().contains("\"companion\""), "她的每个工具都带 companion 参数");
    }

    @Test
    void aChannelOnlySeesTheChannelTools() throws Exception {
        assertEquals(List.of("list_companions", "send", "get_replies", "answer_consent"),
                names(host.tools(token(McpToken.Role.CHANNEL, McpToken.Scope.ALL))));
    }

    // ---- 作用域 ----

    @Test
    void aScopedTokenSeesAndReachesOnlyItsOwnCompanions() throws Exception {
        McpToken scoped = token(McpToken.Role.BRAIN, McpToken.Scope.of(Set.of(ALICE)));

        String listed = call(scoped, "list_companions", new JsonObject()).texts().get(0);
        assertTrue(listed.contains("alice") && !listed.contains("bob"), listed);

        JsonObject toBob = args("companion", "bob");
        toBob.addProperty("text", "hi");
        assertTrue(call(scoped, "say", toBob).error(), "作用域外的同伴不该点得到");
        assertTrue(hands.said.isEmpty());

        JsonObject toAlice = args("companion", "alice");
        toAlice.addProperty("text", "hi");
        McpHost.Result said = call(scoped, "say", toAlice);
        assertEquals("said", said.texts().get(0));
        assertEquals(ALICE, said.subject(), "作用对象给审计");
        assertEquals(List.of(ALICE + ":hi"), hands.said);
    }

    @Test
    void onlyATokenCoveringEveryoneMaySummon() throws Exception {
        assertTrue(call(token(McpToken.Role.BRAIN, McpToken.Scope.of(Set.of(ALICE))), "create_companion",
                args("name", "carol")).error());
        assertFalse(call(BRAIN, "create_companion", args("name", "carol")).error());
    }

    // ---- 回执 ----

    @Test
    void aProgramsReceiptCarriesItsEventsThenHerRuntimeStateAndTheBodysProgressIsForwarded() throws Exception {
        hands.events = "<events>x</events>";
        List<String> progress = new ArrayList<>();
        JsonObject lua = args("companion", "alice");
        lua.addProperty("code", "return 1");

        McpHost.Result result = host.call(BRAIN, "lua", lua.toString(), 5, progress::add);

        assertEquals(List.of("ran lua {\"code\":\"return 1\"}", "<events>x</events>",
                "<runtime_state>of " + ALICE + "</runtime_state>"), result.texts());
        assertFalse(result.error());
        assertEquals(ALICE, result.subject());
        assertEquals(List.of("step 1", "step 2"), progress);
    }

    @Test
    void getEventsEndsWithHerRuntimeStateEvenWhenNothingCame() throws Exception {
        JsonObject poll = args("companion", "alice");
        poll.addProperty("wait_seconds", 0);

        McpHost.Result result = call(BRAIN, "get_events", poll);

        assertTrue(result.texts().get(0).contains("no new events"), result.texts().get(0));
        assertEquals("<runtime_state>of " + ALICE + "</runtime_state>", result.texts().get(1));
    }

    @Test
    void guideHandsOverTheSystemPromptOfThatCompanion() throws Exception {
        McpHost.Result result = call(BRAIN, "guide", args("companion", "alice"));

        assertEquals(List.of("THE GUIDE of " + ALICE), result.texts());
        assertFalse(result.error());
        assertEquals(ALICE, result.subject());
    }

    @Test
    void theInstructionsStayUnderTheLengthClientsCutThemAtAndSendTheReaderToTheGuide() {
        String text = host.instructions();

        assertTrue(text.length() <= McpInstructions.MAX_LENGTH, "说明太长会被客户端截掉: " + text.length());
        assertTrue(text.contains("guide(companion)") && text.contains("get_events") && text.contains("say("), text);
    }

    // ---- 渠道 ----

    @Test
    void aChannelsWordsGoInAndHerAnswerComesBackToThatChannelOnly() throws Exception {
        McpToken mine = token(McpToken.Role.CHANNEL, McpToken.Scope.of(Set.of(ALICE)));
        McpToken other = token(McpToken.Role.CHANNEL, McpToken.Scope.ALL);
        JsonObject words = args("companion", "alice");
        words.addProperty("text", "你好");
        assertFalse(call(mine, "send", words).error());
        assertEquals(List.of(ALICE + ":你好"), hands.heard);

        JsonObject wait = new JsonObject();
        wait.addProperty("wait_seconds", 1);
        JsonObject replies = JsonParser.parseString(call(mine, "get_replies", wait).texts().get(0)).getAsJsonObject();
        assertEquals(1, replies.getAsJsonArray("items").size());
        JsonObject item = replies.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("said", item.get("type").getAsString());
        assertEquals("heard: 你好", item.get("text").getAsString());

        JsonObject nothing = JsonParser.parseString(call(other, "get_replies", wait).texts().get(0)).getAsJsonObject();
        assertEquals(0, nothing.getAsJsonArray("items").size(), "别的渠道不该收到这一份");
    }

    @Test
    void aChannelCannotDriveOrSpeakToCompanionsOutsideItsScope() throws Exception {
        McpToken scoped = token(McpToken.Role.CHANNEL, McpToken.Scope.of(Set.of(ALICE)));
        JsonObject toBob = args("companion", "bob");
        toBob.addProperty("text", "hi");

        assertTrue(call(scoped, "send", toBob).error());
        assertTrue(hands.heard.isEmpty());
        assertTrue(call(scoped, "say", toBob).error(), "渠道不能驾驶");
        assertTrue(call(scoped, "get_events", args("companion", "alice")).error());
        assertTrue(hands.said.isEmpty());
    }

    @Test
    void theOwnersAnswerOnAChannelGoesToTheDeskLikeAnyOtherAndANoteRidesOnlyWithADenial() throws Exception {
        McpToken channel = token(McpToken.Role.CHANNEL, McpToken.Scope.ALL);
        JsonObject answer = args("companion", "alice");
        answer.addProperty("id", 7);
        answer.addProperty("decision", "remember");

        assertFalse(call(channel, "answer_consent", answer).error());
        assertEquals(List.of(ALICE + "#7:ALLOW_REMEMBER:"), hands.answers);

        JsonObject allowWithNote = args("companion", "alice");
        allowWithNote.addProperty("id", 8);
        allowWithNote.addProperty("decision", "allow");
        allowWithNote.addProperty("note", "sneaky");
        assertTrue(call(channel, "answer_consent", allowWithNote).error(), "附言只随拒绝");
        assertEquals(1, hands.answers.size());
    }
}
