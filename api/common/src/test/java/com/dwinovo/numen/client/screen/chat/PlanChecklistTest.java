package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanChecklistTest {

    private static LlmToolCall call(String name, String args) {
        return new LlmToolCall("c1", name, args);
    }

    private static final String PLAN = """
            {"todos":[
              {"content":"砍树","status":"completed","priority":"high"},
              {"content":"做工作台","status":"in_progress","priority":"medium"},
              {"content":"做木镐","status":"pending","priority":"low"},
              {"content":"找铁","status":"cancelled","priority":"low"}]}""";

    @Test
    void readsItemsInOrderWithTheirStates() {
        List<PlanChecklist.Item> items = PlanChecklist.of(call("todowrite", PLAN));
        assertEquals(List.of(
                new PlanChecklist.Item("砍树", PlanChecklist.State.COMPLETED),
                new PlanChecklist.Item("做工作台", PlanChecklist.State.IN_PROGRESS),
                new PlanChecklist.Item("做木镐", PlanChecklist.State.PENDING),
                new PlanChecklist.Item("找铁", PlanChecklist.State.CANCELLED)), items);
        assertEquals(1, PlanChecklist.done(items));
    }

    @Test
    void otherToolsAreNotChecklists() {
        assertNull(PlanChecklist.of(call("mine", PLAN)));
    }

    @Test
    void unreadableArgumentsAreNotChecklists() {
        assertNull(PlanChecklist.of(call("todowrite", "not json")));
        assertNull(PlanChecklist.of(call("todowrite", "[]")));
        assertNull(PlanChecklist.of(call("todowrite", "{}")));
        assertNull(PlanChecklist.of(call("todowrite", "{\"todos\":[]}")));
        assertNull(PlanChecklist.of(call("todowrite", "{\"todos\":[{\"content\":\" \",\"status\":\"pending\"}]}")));
        assertNull(PlanChecklist.of(call("todowrite", "{\"todos\":[{\"content\":\"a\",\"status\":\"done\"}]}")));
        assertNull(PlanChecklist.of(call("todowrite", "{\"todos\":[\"a\"]}")));
    }

    @Test
    void samePlanIsSameContentsRegardlessOfState() {
        List<PlanChecklist.Item> before = PlanChecklist.of(call("todowrite", PLAN));
        List<PlanChecklist.Item> after = PlanChecklist.of(call("todowrite", PLAN
                .replace("\"in_progress\"", "\"completed\"").replace("\"pending\"", "\"in_progress\"")));
        assertTrue(PlanChecklist.sameItems(before, after));
        assertEquals(2, PlanChecklist.done(after));
    }

    @Test
    void changedOrAddedItemsMakeANewPlan() {
        List<PlanChecklist.Item> before = PlanChecklist.of(call("todowrite", PLAN));
        assertFalse(PlanChecklist.sameItems(before,
                PlanChecklist.of(call("todowrite", PLAN.replace("做木镐", "做石镐")))));
        assertFalse(PlanChecklist.sameItems(before, before.subList(0, 3)));
    }
}
