package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.screen.chat.PlanChecklist;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记计划的工具,从模型的入口调:每次交整份计划,收下的那一份就是对话流画的清单,下一次交的整份替换它;还有没做完的,恰好一项在做;
 * 整份都了结了也收。写法错了当场说。
 */
class TodoToolTest {

    /** 调一次:参数是那段 JSON 文本,交回这次调用的结果。 */
    private static String call(String args) {
        AtomicReference<String> result = new AtomicReference<>();
        new TodoTool().invoke(new ToolCall("t1", TodoTool.NAME, args, null, result::set));
        return result.get();
    }

    private static boolean success(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("success").getAsBoolean();
    }

    /** 这次调用在对话流里画成的清单。 */
    private static List<TodoTool.Item> shown(String args) {
        return PlanChecklist.of(new LlmToolCall("t1", TodoTool.NAME, args), call(args));
    }

    @Test
    void eachCallGivesTheWholePlanAndReplacesTheOneBefore() {
        String first = "{\"items\": [\"[>] find iron\", \"[ ] mine iron\", \"[ ] smelt it\"]}";
        String second = "{\"items\": [\"[x] find iron\", \"[>] mine iron with a stone pickaxe\"]}";
        String result = call(first);
        assertTrue(success(result), result);
        assertTrue(result.contains("plan written: 0/3 done; doing now: find iron"), result);
        assertEquals(List.of(new TodoTool.Item("find iron", TodoTool.Status.IN_PROGRESS),
                new TodoTool.Item("mine iron", TodoTool.Status.PENDING),
                new TodoTool.Item("smelt it", TodoTool.Status.PENDING)), shown(first));
        assertEquals(List.of(new TodoTool.Item("find iron", TodoTool.Status.COMPLETED),
                new TodoTool.Item("mine iron with a stone pickaxe", TodoTool.Status.IN_PROGRESS)), shown(second));
    }

    @Test
    void whileWorkRemainsExactlyOneStepIsInProgress() {
        for (String plan : List.of("{\"items\": [\"[ ] find iron\", \"[ ] mine iron\"]}",
                "{\"items\": [\"[>] find iron\", \"[>] mine iron\"]}")) {
            String result = call(plan);
            assertFalse(success(result), result);
            assertTrue(result.contains("exactly one step is [>]"), result);
            assertNull(shown(plan), "a plan that was not taken is no checklist: " + plan);
        }
    }

    @Test
    void anAllSettledPlanIsTaken() {
        assertTrue(success(call("{\"items\": [\"[x] find iron\", \"[-] mine iron\"]}")));
    }

    @Test
    void anItemWithoutItsMarkOrWithNothingToDoIsSaidOnTheSpot() {
        for (String item : List.of("find iron", "[>]   ", "[done] find iron")) {
            String result = call("{\"items\": [\"" + item + "\"]}");
            assertFalse(success(result), result);
            assertTrue(result.contains("must start with [ ] (to do)") || result.contains("nothing to do"), result);
        }
    }
}
