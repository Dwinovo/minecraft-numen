package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.scope.Handle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 一个问题的回答者:各有名字,按登记先后排;句柄只撤自己登记的那一条,撤两次等于撤一次,撤掉的名字可以再登记。 */
class QuestionTest {

    @Test
    void answerersComeInRegistrationOrderAndAHandleWithdrawsOnlyItsOwn() {
        Question<String> question = Question.of("gt:order");
        Handle first = question.register("zeta", "a");
        question.register("alpha", "b");
        assertEquals(List.of("zeta", "alpha"), List.copyOf(question.answerers().keySet()));
        assertEquals(Map.of("zeta", "a", "alpha", "b"), question.answerers());
        first.close();
        first.close();
        assertEquals(Map.of("alpha", "b"), question.answerers());
        question.register("zeta", "c");
        assertEquals(List.of("alpha", "zeta"), List.copyOf(question.answerers().keySet()));
        first.close();
        assertEquals("c", question.answerers().get("zeta"), "a spent handle does not take the next holder of its name");
    }

    @Test
    void aQuestionNeedsAnId() {
        assertThrows(IllegalArgumentException.class, () -> Question.of(" "));
    }
}
