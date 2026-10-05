package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.TodoTool;
import com.dwinovo.numen.agent.tool.TodoTool.Item;
import com.dwinovo.numen.agent.tool.TodoTool.Status;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanChecklistTest {

    private static final List<String> PLAN = List.of("[x] 砍树", "[>] 做工作台", "[ ] 做木镐", "[-] 找铁");
    private static final String TAKEN = "{\"success\":true,\"message\":\"plan written: 1/4 done; doing now: 做工作台\"}";

    /** 一次工具调用:工具名与参数里的 {@code items}。 */
    private static LlmToolCall call(String tool, List<String> items) {
        JsonArray list = new JsonArray();
        items.forEach(list::add);
        JsonObject args = new JsonObject();
        args.add("items", list);
        return new LlmToolCall("c1", tool, args.toString());
    }

    @Test
    void readsItemsInOrderWithTheirStates() {
        List<Item> items = PlanChecklist.of(call(TodoTool.NAME, PLAN), TAKEN);
        assertEquals(List.of(
                new Item("砍树", Status.COMPLETED),
                new Item("做工作台", Status.IN_PROGRESS),
                new Item("做木镐", Status.PENDING),
                new Item("找铁", Status.CANCELLED)), items);
        assertEquals(1, PlanChecklist.done(items));
    }

    @Test
    void otherToolsAndRefusedOrUnansweredCallsAreNotChecklists() {
        assertNull(PlanChecklist.of(call("lua", PLAN), TAKEN));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, PLAN), null));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, PLAN),
                "{\"success\":false,\"message\":\"while work remains exactly one step is [>]\"}"));
    }

    @Test
    void unreadablePlansAreNotChecklists() {
        assertNull(PlanChecklist.of(new LlmToolCall("c1", TodoTool.NAME, "not json"), TAKEN));
        assertNull(PlanChecklist.of(new LlmToolCall("c1", TodoTool.NAME, "{}"), TAKEN));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, List.of()), TAKEN));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, List.of("[ ]  ")), TAKEN));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, List.of("[done] a")), TAKEN));
        assertNull(PlanChecklist.of(call(TodoTool.NAME, List.of("a")), TAKEN));
    }

    @Test
    void samePlanIsSameContentsRegardlessOfState() {
        List<Item> before = PlanChecklist.of(call(TodoTool.NAME, PLAN), TAKEN);
        List<Item> after = PlanChecklist.of(call(TodoTool.NAME,
                List.of("[x] 砍树", "[x] 做工作台", "[>] 做木镐", "[-] 找铁")), TAKEN);
        assertTrue(PlanChecklist.sameItems(before, after));
        assertEquals(2, PlanChecklist.done(after));
    }

    @Test
    void changedOrAddedItemsMakeANewPlan() {
        List<Item> before = PlanChecklist.of(call(TodoTool.NAME, PLAN), TAKEN);
        assertFalse(PlanChecklist.sameItems(before, PlanChecklist.of(call(TodoTool.NAME,
                List.of("[x] 砍树", "[>] 做工作台", "[ ] 做石镐", "[-] 找铁")), TAKEN)));
        assertFalse(PlanChecklist.sameItems(before, before.subList(0, 3)));
    }

    @Test
    void anItemIsReadWithItsMark() {
        assertEquals(new Item("dig the iron", Status.IN_PROGRESS), Item.parse("[>] dig the iron"));
        assertEquals(new Item("smelt it", Status.COMPLETED), Item.parse(" [X] smelt it "));
    }
}
