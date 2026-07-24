package com.dwinovo.numen.core.tools;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolsTest {

    private final AgentTools tools = new AgentTools();

    @Test
    void acceptsExactlyOneInProgressWhileWorkRemains() {
        String result = tools.todowrite(List.of(
                todo("find iron", "in_progress"),
                todo("mine iron", "pending"),
                todo("get pickaxe", "completed")));

        assertTrue(success(result));
        assertTrue(result.contains("continue only the in_progress item"));
    }

    @Test
    void rejectsPlansWithZeroOrSeveralInProgressItems() {
        assertFalse(success(tools.todowrite(List.of(
                todo("find iron", "pending"),
                todo("mine iron", "pending")))));
        assertFalse(success(tools.todowrite(List.of(
                todo("find iron", "in_progress"),
                todo("mine iron", "in_progress")))));
    }

    @Test
    void allTerminalOrEmptyPlanIsValid() {
        assertTrue(success(tools.todowrite(List.of(
                todo("find iron", "completed"),
                todo("mine iron", "cancelled")))));
        assertTrue(success(tools.todowrite(List.of())));
    }

    @Test
    void rejectsMissingOrBlankPlanContent() {
        assertThrows(IllegalArgumentException.class, () -> tools.todowrite(null));
        assertThrows(IllegalArgumentException.class, () ->
                tools.todowrite(List.of(todo(" ", "in_progress"))));
    }

    private static AgentTools.Todo todo(String content, String status) {
        return new AgentTools.Todo(content, status, "medium");
    }

    private static boolean success(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("success").getAsBoolean();
    }
}
