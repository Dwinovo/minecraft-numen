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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件那扇门:登记的种类就是类型表里和内置事件同一形状的一行,内置的行改不了;客户端那一侧只收主人的话和
 * 登记过的世界事件,没有主人客户端的地方如实说没送出去;某个插件的状态片段算炸了只丢它自己那段。
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

    @Test
    void aPluginCannotTakeOverABuiltInType() {
        assertThrows(IllegalArgumentException.class, () -> door().registerEventType(EventTypes.DEATH, false),
                "死亡恒为急件是引擎的语义,插件改不了");
        assertTrue(EventTypes.get(EventTypes.DEATH).alwaysUrgent());
    }

    @Test
    void theClientDoorTakesTheOwnersWordsAndRegisteredWorldEventsOnly() {
        NumenApi numen = door();
        numen.registerEventType("gametest_stream_comment", false);
        UUID companion = UUID.randomUUID();

        assertEquals(Delivery.REJECTED, numen.emit(companion, "gametest_stream_comment", "弹幕:唱首歌"),
                "登记过的世界事件收;这里没有主人客户端,如实说没送出去");
        assertThrows(IllegalArgumentException.class, () -> numen.emit(companion, "谁也没登记过", "x"),
                "没登记的种类不管在哪一侧都拒");
        for (String control : List.of(EventTypes.GOAL, EventTypes.COMPACT, EventTypes.CLEAR)) {
            assertThrows(IllegalArgumentException.class, () -> numen.emit(companion, control, "x"),
                    control + " 是引擎自己的输入,不从门外进");
        }
    }

    @Test
    void aFailingFragmentIsSkippedUntilItComputesAgain() {
        NumenApi numen = door();
        UUID self = UUID.randomUUID();
        boolean[] broken = {true};
        numen.contributeState(id -> {
            if (!id.equals(self)) return "";
            if (broken[0]) throw new IllegalStateException("gametest: 读不到");
            return "<gametest_broken>ok</gametest_broken>";
        });
        numen.contributeState(id -> id.equals(self) ? "<gametest_fine>ok</gametest_fine>" : "");

        for (int tick = 0; tick < 3; tick++) {
            assertEquals("<gametest_fine>ok</gametest_fine>", NumenPlugins.stateFragments(self),
                    "算炸的那段丢掉,后面的照常挂上");
        }
        broken[0] = false;
        assertEquals("<gametest_broken>ok</gametest_broken><gametest_fine>ok</gametest_fine>",
                NumenPlugins.stateFragments(self), "恢复以后那段回来");
    }
}
