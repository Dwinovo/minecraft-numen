package com.dwinovo.numen.api.client.screen.chat;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.tool.Asking;
import com.dwinovo.numen.api.agent.tool.Questions.Answer;
import com.dwinovo.numen.api.agent.tool.Questions.Option;
import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.dwinovo.numen.api.agent.tool.Questions.Reply;
import com.dwinovo.numen.api.client.agent.CompanionView;
import com.dwinovo.numen.api.client.ui.KeyCodes;
import net.minecraft.client.resources.language.NumenTestLanguage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 她挂着提问时输入行被面板接管:数字键选、回车交"其他"、Esc 是现在不想答;话不会当成发给她的话送出去。 */
class ChatInputBarQuestionTest {

    private static final int KEY_1 = 49;
    private static final int KEY_2 = 50;

    @BeforeAll
    static void english() {
        NumenTestLanguage.install();
    }

    private static final class Host implements ChatInputBar.Host {
        Asking asking;
        final List<String> sent = new ArrayList<>();

        @Override public void onSend(String text) { sent.add(text); }
        @Override public void onAbort() { }
        @Override public boolean canAbort() { return false; }
        @Override public String hint() { return ""; }
        @Override public CompanionView loop() { return null; }
        @Override public Conversation conversation() { return null; }
        @Override public void onCommandReply(String reply) { }
        @Override public Asking asking() { return asking != null && asking.open() ? asking : null; }
    }

    private final List<Reply> replies = new ArrayList<>();
    private final Host host = new Host();

    private static Question single(String text) {
        return new Question(text, "h", List.of(new Option("a", "first"), new Option("b", "second")), false);
    }

    private static Question multi(String text) {
        return new Question(text, "h", List.of(new Option("x", ""), new Option("y", ""), new Option("z", "")), true);
    }

    private ChatInputBar ask(Question... questions) {
        host.asking = new Asking(List.of(questions), replies::add);
        ChatInputBar bar = new ChatInputBar(host, EnumSet.of(ChatInputBar.Key.SEND));
        bar.build(0, 0, 200, 20, 0);
        return bar;
    }

    @Test
    void aNumberKeyPicksTheOptionAndTheDigitIsNotTypedIntoTheBox() {
        ChatInputBar bar = ask(single("Q?"));
        assertTrue(bar.keyPressed(KEY_2, 0));
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q?", "b", null)))), replies);
        assertTrue(bar.charTyped('2'), "the character that follows the key press is swallowed");
        assertEquals("", bar.text());
    }

    @Test
    void enterHandsInWhatIsWrittenInTheBoxAsTheOtherAnswerAndNothingIsSaidToHer() {
        ChatInputBar bar = ask(single("Q?"));
        bar.setText("the one by the lake");
        assertTrue(bar.keyPressed(KeyCodes.ENTER, 0));
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q?", "the one by the lake", null)))), replies);
        assertTrue(host.sent.isEmpty(), "the words are an answer, not a message");
        assertEquals("", bar.text());
    }

    @Test
    void withWordsInTheBoxANumberIsJustTheNumber() {
        ChatInputBar bar = ask(single("Q?"));
        bar.setText("room ");
        assertFalse(bar.keyPressed(KEY_1, 0), "left to the box to type");
        assertTrue(replies.isEmpty());
    }

    @Test
    void escapeSaysNotNow() {
        ChatInputBar bar = ask(single("Q?"));
        assertTrue(bar.keyPressed(KeyCodes.ESCAPE, 0));
        assertEquals(List.of(new Reply.Declined()), replies);
    }

    @Test
    void severalQuestionsAndAMultiSelectEndInAReviewThatEnterSubmits() {
        ChatInputBar bar = ask(single("Q1?"), multi("Q2?"));
        bar.keyPressed(KEY_1, 0);
        bar.keyPressed(KEY_1, 0);
        bar.keyPressed(KEY_2, 0);
        assertTrue(replies.isEmpty(), "toggling picks does not hand in anything");
        bar.keyPressed(KeyCodes.ENTER, 0);   // box empty: next
        assertTrue(host.asking.reviewing());
        bar.keyPressed(KeyCodes.ENTER, 0);   // review: submit
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q1?", "a", null), new Answer("Q2?", "x, y", null)))),
                replies);
    }

    @Test
    void theLeftKeyGoesBackToTheQuestionBefore() {
        ChatInputBar bar = ask(single("Q1?"), single("Q2?"));
        bar.keyPressed(KEY_1, 0);
        assertEquals(1, host.asking.step());
        bar.keyPressed(KeyCodes.LEFT, 0);
        assertEquals(0, host.asking.step());
    }

    @Test
    void theWordsWrittenBeforeTheQuestionComeBackAfterIt() {
        host.asking = null;
        ChatInputBar bar = new ChatInputBar(host, EnumSet.of(ChatInputBar.Key.SEND));
        bar.build(0, 0, 200, 20, 0);
        bar.setText("half a message");

        host.asking = new Asking(List.of(single("Q?")), replies::add);
        bar.refreshEnablement();
        assertEquals("", bar.text(), "the draft is put aside so it cannot become the answer");

        host.asking.decline();
        bar.refreshEnablement();
        assertEquals("half a message", bar.text());
    }
}
