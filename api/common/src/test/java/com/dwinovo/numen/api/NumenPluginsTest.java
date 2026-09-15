package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件那扇门上与事件有关的两件事:登记的种类就是类型表里和内置事件同一形状的一行;
 * 没有主人客户端的地方,主人的话送不出去,如实说没送出去。
 */
class NumenPluginsTest {

    private static NumenApi door() {
        AtomicReference<NumenApi> api = new AtomicReference<>();
        NumenPlugins.register(api::set);
        return api.get();
    }

    @Test
    void aRegisteredEventTypeIsARowShapedLikeTheBuiltInEvents() {
        NumenApi numen = door();
        numen.registerEventType("gametest_accessory_changed", false);
        numen.registerEventType("gametest_accessory_broke", true);

        for (String id : List.of("gametest_accessory_changed", "gametest_accessory_broke")) {
            assertTrue(EventTypes.isRegistered(id), id);
            EventTypes.Type row = EventTypes.get(id);
            EventTypes.Type builtIn = EventTypes.get(EventTypes.REFLEX);
            assertEquals(builtIn.delivery(), row.delivery(), id + " 与内置事件同样插话投递");
            assertEquals(builtIn.clearedByInterrupt(), row.clearedByInterrupt(), id + " 按了停止也还在");
            assertEquals(builtIn.fromOwner(), row.fromOwner(), id + " 不是主人说的");
            assertNull(row.chatPreview().apply("x"), id + " 不进聊天流");
        }
        assertFalse(EventTypes.get("gametest_accessory_changed").alwaysUrgent());
        assertTrue(EventTypes.get("gametest_accessory_broke").alwaysUrgent());

        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        assertTrue(q.push("gametest_accessory_broke", "<event kind=\"gametest_accessory_broke\">碎了</event>", 0L, false),
                "登记成恒急的,发送方没标也是急件");
    }

    @Test
    void withoutTheOwnersClientTheOwnersWordsAreNotDelivered() {
        assertEquals(Delivery.REJECTED, door().emit(UUID.randomUUID(), EventTypes.QUERY, "在吗"),
                "专用服务器上没有主人的客户端");
    }
}
