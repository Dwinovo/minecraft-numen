package com.dwinovo.numen.cli;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 她的计划:还有没做完的,恰好一项在做;整份都了结了、或一项都没有,都收。一项的写法错了当场说。 */
class TodoCommandsTest {

    private static List<TodoCommands.Item> plan(String... items) {
        return Arrays.stream(items).map(TodoCommands.Item::parse).toList();
    }

    private static boolean success(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("success").getAsBoolean();
    }

    @Test
    void acceptsExactlyOneInProgressWhileWorkRemains() {
        String result = TodoCommands.write(plan("[>] find iron", "[ ] mine iron", "[x] get pickaxe"));
        assertTrue(success(result));
        assertTrue(result.contains("doing now: find iron"), result);
    }

    @Test
    void rejectsPlansWithZeroOrSeveralInProgressItems() {
        assertThrows(IllegalArgumentException.class, () -> TodoCommands.write(plan("[ ] find iron", "[ ] mine iron")));
        assertThrows(IllegalArgumentException.class, () -> TodoCommands.write(plan("[>] find iron", "[>] mine iron")));
    }

    @Test
    void allTerminalOrEmptyPlanIsValid() {
        assertTrue(success(TodoCommands.write(plan("[x] find iron", "[-] mine iron"))));
        assertTrue(success(TodoCommands.write(List.of())));
    }

    @Test
    void rejectsMissingOrBlankPlanContent() {
        assertThrows(IllegalArgumentException.class, () -> TodoCommands.Item.parse("[>]   "));
        assertThrows(IllegalArgumentException.class, () -> TodoCommands.Item.parse("find iron"));
    }
}
