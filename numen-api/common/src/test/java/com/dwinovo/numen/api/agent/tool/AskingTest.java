package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.tool.Questions.Answer;
import com.dwinovo.numen.api.agent.tool.Questions.Option;
import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.dwinovo.numen.api.agent.tool.Questions.Reply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主人在提问面板上的答题路径:一次一个问题、多选、"其他"、确认一步、回上一题、拒绝与想先聊聊。 */
class AskingTest {

    private static Question single(String text) {
        return new Question(text, "h", List.of(new Option("a", "first"), new Option("b", "second")), false);
    }

    private static Question multi(String text) {
        return new Question(text, "h", List.of(new Option("x", ""), new Option("y", ""), new Option("z", "")), true);
    }

    private final List<Reply> replies = new ArrayList<>();

    private Asking asking(Question... questions) {
        return new Asking(List.of(questions), replies::add);
    }

    @Test
    void aSingleSingleSelectQuestionIsAnsweredByPicking() {
        Asking asking = asking(single("Q1?"));
        assertTrue(asking.direct());
        asking.pick(1);
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q1?", "b", null)))), replies);
    }

    @Test
    void severalQuestionsGoOneAtATimeAndEndInAReviewThatSubmits() {
        Asking asking = asking(single("Q1?"), single("Q2?"));
        assertFalse(asking.direct());
        assertEquals("Q1?", asking.current().question());
        asking.pick(0);
        assertEquals("Q2?", asking.current().question());
        assertTrue(replies.isEmpty());
        asking.pick(1);
        assertTrue(asking.reviewing());
        assertNull(asking.current());
        assertTrue(replies.isEmpty(), "nothing is handed in before the owner confirms");
        asking.submit();
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q1?", "a", null), new Answer("Q2?", "b", null)))),
                replies);
    }

    @Test
    void aMultiSelectQuestionTogglesAndNeedsNextEvenAlone() {
        Asking asking = asking(multi("Q?"));
        assertFalse(asking.direct());
        asking.next();
        assertEquals(0, asking.step(), "nothing picked yet: next does not move");
        asking.pick(2);
        asking.pick(0);
        asking.pick(2);
        assertTrue(asking.picked(0, 0));
        assertFalse(asking.picked(0, 2), "picking twice toggles it off");
        asking.next();
        assertTrue(asking.reviewing());
        asking.submit();
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q?", "x", null)))), replies);
    }

    @Test
    void anOwnWordsAnswerReplacesASingleSelectPickAndAddsToAMultiSelect() {
        Asking asking = asking(single("Q1?"), multi("Q2?"));
        asking.pick(0);
        asking.back();
        asking.other("  neither ");
        assertEquals("neither", asking.answer(0));
        assertFalse(asking.picked(0, 0));

        asking.pick(1);
        asking.other("and more");
        assertEquals("y, and more", asking.answer(1));
        asking.submit();
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q1?", "neither", null),
                new Answer("Q2?", "y, and more", null)))), replies);
    }

    @Test
    void anEmptyOwnAnswerDoesNothing() {
        Asking asking = asking(single("Q?"));
        asking.other("   ");
        assertTrue(replies.isEmpty());
        assertEquals(0, asking.step());
    }

    @Test
    void goingBackKeepsWhatWasPickedAndAllowsChangingIt() {
        Asking asking = asking(single("Q1?"), single("Q2?"));
        asking.pick(0);
        asking.back();
        assertEquals(0, asking.step());
        assertTrue(asking.picked(0, 0));
        asking.pick(1);
        asking.pick(0);
        asking.submit();
        assertEquals(List.of(new Reply.Answered(List.of(new Answer("Q1?", "b", null), new Answer("Q2?", "a", null)))),
                replies);
    }

    @Test
    void submitBeforeTheReviewDoesNothing() {
        Asking asking = asking(single("Q1?"), single("Q2?"));
        asking.pick(0);
        asking.submit();
        assertTrue(replies.isEmpty());
    }

    @Test
    void declineAndClarifyEndItAtOnceAndOnlyOnce() {
        Asking asking = asking(single("Q1?"), single("Q2?"));
        asking.pick(0);
        asking.clarify();
        asking.decline();
        asking.pick(1);
        assertEquals(List.of(new Reply.Clarify(List.of(new Answer("Q1?", "a", null)))), replies);
        assertFalse(asking.open());

        replies.clear();
        Asking another = asking(single("Q?"));
        another.decline();
        assertEquals(List.of(new Reply.Declined()), replies);
    }

    @Test
    void aWithdrawnQuestionNeverReplies() {
        QuestionDesk desk = new QuestionDesk();
        Asking asking = desk.ask(List.of(single("Q?")), replies::add);
        desk.withdraw(asking);
        asking.pick(0);
        assertTrue(replies.isEmpty());
        assertNull(desk.pending());
    }
}
