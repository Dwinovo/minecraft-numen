package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.scope.Handle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 一个问题的回答者:按登记先后排;句柄只撤自己登记的那一条,撤两次等于撤一次。 */
class QuestionTest {

    @Test
    void answerersComeInRegistrationOrderAndAHandleWithdrawsOnlyItsOwn() {
        Question<String> question = Question.of("gt:order");
        Handle first = question.register("a");
        Handle again = question.register("a");
        question.register("b");
        assertEquals(List.of("a", "a", "b"), question.answerers());
        first.close();
        first.close();
        assertEquals(List.of("a", "b"), question.answerers());
        again.close();
        assertEquals(List.of("b"), question.answerers());
    }

    @Test
    void aQuestionNeedsAnId() {
        assertThrows(IllegalArgumentException.class, () -> Question.of(" "));
    }
}
