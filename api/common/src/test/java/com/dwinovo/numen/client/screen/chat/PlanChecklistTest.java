package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.cli.TodoCommands;
import com.dwinovo.numen.cli.TodoCommands.Item;
import com.dwinovo.numen.cli.TodoCommands.Status;
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

    /** 一次脚本运行的回执:数据里留着这几次调用的参数(函数名、参数表)。 */
    private static String receipt(JsonObject... echoed) {
        JsonArray calls = new JsonArray();
        for (JsonObject e : echoed) {
            calls.add(e);
        }
        JsonObject data = new JsonObject();
        data.addProperty("status", "ok");
        data.add("echoed", calls);
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("message", "The script ran to the end: 1 call in 0 s.");
        root.add("data", data);
        return root.toString();
    }

    private static JsonObject echo(String function, List<String> items) {
        JsonArray list = new JsonArray();
        items.forEach(list::add);
        JsonObject args = new JsonObject();
        args.add("items", list);
        JsonObject echo = new JsonObject();
        echo.addProperty("function", function);
        echo.add("args", args);
        return echo;
    }

    @Test
    void readsItemsInOrderWithTheirStates() {
        List<Item> items = PlanChecklist.of(receipt(echo("todo.write", PLAN)));
        assertEquals(List.of(
                new Item("砍树", Status.COMPLETED),
                new Item("做工作台", Status.IN_PROGRESS),
                new Item("做木镐", Status.PENDING),
                new Item("找铁", Status.CANCELLED)), items);
        assertEquals(1, PlanChecklist.done(items));
    }

    @Test
    void theLastPlanOfARunIsTheOneShown() {
        List<Item> items = PlanChecklist.of(receipt(echo("todo.write", PLAN),
                echo("todo.write", List.of("[x] 砍树", "[x] 做工作台", "[>] 做木镐", "[-] 找铁"))));
        assertEquals(2, PlanChecklist.done(items));
    }

    @Test
    void otherCallsAreNotChecklists() {
        assertNull(PlanChecklist.of(receipt(echo("mine.run", PLAN))));
        assertNull(PlanChecklist.of(receipt()));
    }

    @Test
    void unreadableReceiptsAreNotChecklists() {
        assertNull(PlanChecklist.of(null));
        assertNull(PlanChecklist.of("not json"));
        assertNull(PlanChecklist.of("[]"));
        assertNull(PlanChecklist.of("{}"));
        assertNull(PlanChecklist.of(receipt(echo("todo.write", List.of()))));
        assertNull(PlanChecklist.of(receipt(echo("todo.write", List.of("[ ]  ")))));
        assertNull(PlanChecklist.of(receipt(echo("todo.write", List.of("[done] a")))));
        assertNull(PlanChecklist.of(receipt(echo("todo.write", List.of("a")))));
    }

    @Test
    void samePlanIsSameContentsRegardlessOfState() {
        List<Item> before = PlanChecklist.of(receipt(echo("todo.write", PLAN)));
        List<Item> after = PlanChecklist.of(receipt(echo("todo.write",
                List.of("[x] 砍树", "[x] 做工作台", "[>] 做木镐", "[-] 找铁"))));
        assertTrue(PlanChecklist.sameItems(before, after));
        assertEquals(2, PlanChecklist.done(after));
    }

    @Test
    void changedOrAddedItemsMakeANewPlan() {
        List<Item> before = PlanChecklist.of(receipt(echo("todo.write", PLAN)));
        assertFalse(PlanChecklist.sameItems(before, PlanChecklist.of(receipt(echo("todo.write",
                List.of("[x] 砍树", "[>] 做工作台", "[ ] 做石镐", "[-] 找铁"))))));
        assertFalse(PlanChecklist.sameItems(before, before.subList(0, 3)));
    }

    @Test
    void anItemIsReadWithItsMark() {
        assertEquals(new Item("dig the iron", Status.IN_PROGRESS), TodoCommands.Item.parse("[>] dig the iron"));
        assertEquals(new Item("smelt it", Status.COMPLETED), TodoCommands.Item.parse(" [X] smelt it "));
    }
}
