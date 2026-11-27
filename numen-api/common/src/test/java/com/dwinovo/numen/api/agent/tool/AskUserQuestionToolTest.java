package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.tool.Questions.Answer;
import com.dwinovo.numen.api.agent.tool.Questions.Reply;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 她问主人选择题:参数的校验、挂起到作答、每种结局她读到的话。 */
class AskUserQuestionToolTest {

    private static final String ONE = """
            {"questions":[{"question":"Which furnace?","header":"Furnace","options":[
              {"label":"Blast","description":"fast ore"},{"label":"Smoker","description":"food"}]}]}""";

    private static final String MANY = """
            {"questions":[
              {"question":"Which furnace?","header":"Furnace","options":[
                {"label":"Blast","description":"fast ore"},{"label":"Smoker","description":"food"}]},
              {"question":"Which fuels?","header":"Fuel","multiSelect":true,"options":[
                {"label":"Coal","description":"c"},{"label":"Wood","description":"w"},{"label":"Lava","description":"l"}]}]}""";

    private final QuestionDesk desk = new QuestionDesk();
    private boolean channel;
    private final AskUserQuestionTool tool = new AskUserQuestionTool();

    private ToolAnchor anchor() {
        return new ToolAnchor() {
            @Override
            public UUID entityUuid() {
                return UUID.randomUUID();
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
            public java.util.concurrent.CompletableFuture<String> searchApi(String query, int maxResults) {
                throw new UnsupportedOperationException();
            }

            @Override
            public QuestionDesk questions() {
                return desk;
            }

            @Override
            public boolean channelVisit() {
                return channel;
            }
        };
    }

    private ToolCall call(String args, AtomicReference<String> result) {
        return new ToolCall("c1", AskUserQuestionTool.NAME, args, anchor(), result::set);
    }

    private static String message(String result) {
        return ToolOutcome.modelText(result);
    }

    // ---- 参数 ----

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String rejected(String json) {
        return assertThrows(IllegalArgumentException.class, () -> Questions.parse(args(json))).getMessage();
    }

    @Test
    void validQuestionsParseWithMultiSelectOffByDefault() {
        List<Questions.Question> parsed = Questions.parse(args(MANY));
        assertEquals(2, parsed.size());
        assertFalse(parsed.get(0).multiSelect());
        assertTrue(parsed.get(1).multiSelect());
        assertEquals(new Questions.Option("Blast", "fast ore"), parsed.get(0).options().get(0));
    }

    @Test
    void theNumberOfQuestionsAndOptionsIsBounded() {
        assertTrue(rejected("{\"questions\":[]}").contains("1-4"));
        String q = "{\"question\":\"Q%d?\",\"header\":\"h\",\"options\":[{\"label\":\"a\",\"description\":\"\"},"
                + "{\"label\":\"b\",\"description\":\"\"}]}";
        String five = "{\"questions\":[" + String.join(",", q.formatted(1), q.formatted(2), q.formatted(3), q.formatted(4),
                q.formatted(5)) + "]}";
        assertTrue(rejected(five).contains("1-4"));
        assertTrue(rejected("{\"questions\":[{\"question\":\"Q?\",\"header\":\"h\",\"options\":[{\"label\":\"a\","
                + "\"description\":\"\"}]}]}").contains("2-4 options"));
        assertTrue(rejected("{\"questions\":[{\"question\":\"Q?\",\"header\":\"h\",\"options\":["
                + "{\"label\":\"a\",\"description\":\"\"},{\"label\":\"b\",\"description\":\"\"},"
                + "{\"label\":\"c\",\"description\":\"\"},{\"label\":\"d\",\"description\":\"\"},"
                + "{\"label\":\"e\",\"description\":\"\"}]}]}").contains("2-4 options"));
    }

    @Test
    void questionTextsAndOptionLabelsMustBeUnique() {
        String same = "{\"questions\":[%s,%s]}".formatted(
                "{\"question\":\"Q?\",\"header\":\"h\",\"options\":[{\"label\":\"a\",\"description\":\"\"},{\"label\":\"b\",\"description\":\"\"}]}",
                "{\"question\":\"Q?\",\"header\":\"h\",\"options\":[{\"label\":\"a\",\"description\":\"\"},{\"label\":\"b\",\"description\":\"\"}]}");
        assertTrue(rejected(same).contains("must be unique"));
        assertTrue(rejected("{\"questions\":[{\"question\":\"Q?\",\"header\":\"h\",\"options\":["
                + "{\"label\":\"a\",\"description\":\"x\"},{\"label\":\"a\",\"description\":\"y\"}]}]}")
                .contains("must be unique"));
    }

    @Test
    void theHeaderIsAtMostTwelveCharacters() {
        String header = "{\"questions\":[{\"question\":\"Q?\",\"header\":\"%s\",\"options\":["
                + "{\"label\":\"a\",\"description\":\"\"},{\"label\":\"b\",\"description\":\"\"}]}]}";
        assertEquals(1, Questions.parse(args(header.formatted("123456789012"))).size());
        assertEquals(1, Questions.parse(args(header.formatted("炉子炉子炉子炉子炉子炉子"))).size(), "characters, not bytes");
        assertTrue(rejected(header.formatted("1234567890123")).contains("12"));
    }

    @Test
    void theFieldsTheReferenceKeepsForItsOwnPlumbingAreNotAccepted() {
        assertTrue(rejected(ONE.replace("{\"questions\"", "{\"answers\":{\"Which furnace?\":\"Blast\"},\"questions\""))
                .contains("unknown argument 'answers'"), "she cannot answer for her owner");
    }

    @Test
    void invalidArgumentsFailTheCallAtOnceWithoutAskingAnyone() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call("{\"questions\":[]}", result));
        assertTrue(ToolOutcome.failed(result.get()));
        assertNull(desk.pending(), "nothing is shown to the owner");
    }

    @Test
    void theSchemaAndDescriptionAreTheOnesOfTheReferenceAddressedToTheOwner() {
        assertTrue(tool.description().startsWith("Asks your owner multiple choice questions to gather information, "
                + "clarify ambiguity, understand preferences, make decisions or offer them choices."));
        assertFalse(tool.description().toLowerCase().contains("the user"));
        assertFalse(tool.description().toLowerCase().contains("plan mode"));
        assertFalse(tool.description().contains("preview"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parameterSchema().get("properties");
        assertEquals(List.of("questions"), List.copyOf(properties.keySet()), "no answers/annotations/metadata");
        assertTrue(tool.waitsForOwner());
        assertEquals("Furnace", tool.summary(args(ONE)));
        assertEquals("", tool.summary(new JsonObject()));
    }

    // ---- 挂起与结局 ----

    @Test
    void theCallStaysPendingUntilTheOwnerAnswersAndThenCompletesWithTheAnswer() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(ONE, result));

        Asking asking = desk.pending();
        assertNotNull(asking, "the question is waiting for the owner");
        assertNull(result.get(), "no result before the answer");

        asking.pick(0);
        assertEquals("Your owner has answered your questions: \"Which furnace?\"=\"Blast\". You can now continue "
                + "with your owner's answers in mind.", message(result.get()));
        assertFalse(ToolOutcome.failed(result.get()));
        assertNull(desk.pending(), "answered: the panel is gone");
    }

    @Test
    void severalQuestionsAndAMultiSelectAnswerJoinTheirPicksWithCommas() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(MANY, result));
        Asking asking = desk.pending();
        asking.pick(1);
        asking.pick(0);
        asking.pick(2);
        asking.next();
        asking.submit();
        assertEquals("Your owner has answered your questions: \"Which furnace?\"=\"Smoker\", "
                + "\"Which fuels?\"=\"Coal, Lava\". You can now continue with your owner's answers in mind.",
                message(result.get()));
        assertEquals(List.of(new Answer("Which furnace?", "Smoker"), new Answer("Which fuels?", "Coal, Lava")),
                Questions.answersIn(result.get()), "the stream reads the answers from the result");
    }

    @Test
    void anOwnAnswerIsTheOwnersWordsAsTheyWereWritten() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(ONE, result));
        desk.pending().other("  the one by the lake ");
        assertEquals("Your owner has answered your questions: \"Which furnace?\"=\"the one by the lake\". You can "
                + "now continue with your owner's answers in mind.", message(result.get()));
    }

    @Test
    void escapeDeclinesAndTheCallFailsWithTheRejectText() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(ONE, result));
        desk.pending().decline();
        assertTrue(ToolOutcome.failed(result.get()));
        assertEquals("Your owner doesn't want to answer right now. STOP what you are doing and wait for your owner "
                + "to tell you how to proceed.", message(result.get()));
    }

    @Test
    void chatAboutThisRejectsWithTheQuestionsAndWhatWasAnsweredSoFar() {
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(MANY, result));
        desk.pending().pick(1);
        desk.pending().clarify();
        assertTrue(ToolOutcome.failed(result.get()));
        assertEquals("Your owner doesn't want to answer right now. To tell you how to proceed, your owner said:\n"
                + "The owner wants to clarify these questions.\n"
                + "This means they may have additional information, context or questions for you.\n"
                + "Take their response into account and then reformulate the questions if appropriate.\n"
                + "Start by asking them what they would like to clarify.\n\n"
                + "Questions asked:\n"
                + "- \"Which furnace?\"\n  Answer: Smoker\n"
                + "- \"Which fuels?\"\n  (No answer provided)", message(result.get()));
    }

    @Test
    void duringAChannelVisitTheCallIsRefusedAtOnceWithWhatToDoInstead() {
        channel = true;
        AtomicReference<String> result = new AtomicReference<>();
        tool.invoke(call(ONE, result));
        assertTrue(ToolOutcome.failed(result.get()));
        assertEquals("Your owner is talking to you through a chat channel, not at the game, so there is no panel to "
                + "answer on. Ask in your reply instead.", message(result.get()));
        assertNull(desk.pending(), "nothing hangs on a panel nobody sits at");
    }

    @Test
    void anAbandonedCallWithdrawsTheQuestionAndNeverAnswersAfterwards() {
        AtomicReference<String> result = new AtomicReference<>();
        ToolCall call = call(ONE, result);
        tool.invoke(call);
        Asking asking = desk.pending();

        call.abandon();
        assertNull(desk.pending(), "the panel goes away with the call");
        asking.pick(0);
        assertNull(result.get(), "a late click on a stale panel reports nothing");
    }
}
