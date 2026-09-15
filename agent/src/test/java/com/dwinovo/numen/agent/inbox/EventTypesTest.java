package com.dwinovo.numen.agent.inbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型表是"这类条目怎么处理"的唯一答案。循环不再按类型字符串判断什么时候交给大脑、是不是急件,
 * 事件的种类也就是表里的一行——所以表里每一格都得是对的,这里一格一格钉住。
 */
class EventTypesTest {

    private static final long T0 = 1_000_000L;

    private static void assertRow(String id, EventTypes.Delivery delivery, boolean alwaysUrgent,
                                  boolean clearedByInterrupt, boolean fromOwner, boolean inChat) {
        assertTrue(EventTypes.isRegistered(id), id + " 该在表里");
        EventTypes.Type t = EventTypes.get(id);
        assertEquals(id, t.id());
        assertEquals(delivery, t.delivery(), id + " 的投递方式");
        assertEquals(alwaysUrgent, t.alwaysUrgent(), id + " 恒不恒急");
        assertEquals(clearedByInterrupt, t.clearedByInterrupt(), id + " 打断时清不清");
        assertEquals(fromOwner, t.fromOwner(), id + " 算不算主人说的");
        assertEquals(inChat, t.chatPreview().apply("x") != null, id + " 进不进聊天流");
    }

    @Test
    void builtInTypesAreRegisteredAsTheTableSays() {
        assertRow(EventTypes.QUERY, EventTypes.Delivery.STEER, true, true, true, true);
        assertRow(EventTypes.GOAL, EventTypes.Delivery.FOLLOW_UP, true, true, true, false);
        assertRow(EventTypes.COMPACT, EventTypes.Delivery.CONTROL, true, true, true, true);
        assertRow(EventTypes.CLEAR, EventTypes.Delivery.CONTROL, true, true, true, true);
    }

    @Test
    void everyKindOfWorldEventIsItsOwnRow() {
        // 恒急的是"她不知道,正在做的事就是错的";其余由发送方按事情本身定
        assertRow(EventTypes.TASK_FINISHED, EventTypes.Delivery.STEER, false, false, false, false);
        assertRow(EventTypes.DEATH, EventTypes.Delivery.STEER, true, false, false, false);
        assertRow(EventTypes.HUNGRY, EventTypes.Delivery.STEER, true, false, false, false);
        assertRow(EventTypes.OWNER_HURT, EventTypes.Delivery.STEER, false, false, false, false);
        assertRow(EventTypes.TIMER, EventTypes.Delivery.STEER, true, false, false, false);
        assertRow(EventTypes.WOKE, EventTypes.Delivery.STEER, true, false, false, false);
        assertRow(EventTypes.DIMENSION_CHANGE, EventTypes.Delivery.STEER, false, false, false, false);
        assertRow(EventTypes.REFLEX, EventTypes.Delivery.STEER, false, false, false, false);
        assertRow(EventTypes.DROPPED, EventTypes.Delivery.STEER, false, false, false, false);
    }

    @Test
    void theCatchAllKindsAreGone() {
        assertFalse(EventTypes.isRegistered("event"), "笼统的 event 不再是一种类型");
        assertFalse(EventTypes.isRegistered("body_log"), "身体日志这个桶退役了,本能叙事是 reflex");
    }

    @Test
    void worldEventRowsReachTheModelVerbatimAndStayOutOfChat() {
        EventTypes.Type t = EventTypes.get(EventTypes.REFLEX);
        String wire = "<event kind=\"reflex\" reflex=\"mlg\">broke a fall with a water bucket</event>";

        assertEquals(wire, t.toModel().apply(wire), "文本在发出口就拼好了,查表只原样交出去");
        assertNull(t.chatPreview().apply(wire));
    }

    @Test
    void anUnregisteredTypeIsPlainTextTheSenderDecidesOn() {
        EventTypes.Type t = EventTypes.get("谁也没登记过的类型");
        assertFalse(EventTypes.isRegistered("谁也没登记过的类型"));
        assertEquals(EventTypes.Delivery.STEER, t.delivery(), "没登记的当普通文本交给模型,不当控制命令吞掉");
        assertFalse(t.alwaysUrgent());
        assertFalse(t.clearedByInterrupt(), "别处发来的事实,按了停止也还在");
        assertFalse(t.fromOwner(), "不认识的东西不能冒充主人说的话");
        assertNull(t.chatPreview().apply("x"));
    }

    @Test
    void anUnregisteredTypeKeepsTheUrgencyItArrivedWith() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);

        assertTrue(q.push("服务端插件的类型", "<event kind=\"服务端插件的类型\">急</event>", T0, true));
        assertFalse(q.push("另一种没登记的", "<event>不急</event>", T0, false));
        assertTrue(q.hasUrgent());
        assertEquals(0, q.clearInterrupted(), "一条都不清");
        assertEquals(2, q.size());
    }

    @Test
    void aTypeIdIsRegisteredOnlyOnce() {
        EventTypes.Type query = EventTypes.get(EventTypes.QUERY);
        assertThrows(IllegalArgumentException.class,
                () -> EventTypes.register(EventTypes.event(EventTypes.QUERY, false)), "内置的行改不了");
        assertEquals(query, EventTypes.get(EventTypes.QUERY), "主人的话那一行原样留着");

        EventTypes.register(EventTypes.event("pet_whistled", false));
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> EventTypes.register(EventTypes.event("pet_whistled", true)));
        assertTrue(again.getMessage().contains("pet_whistled"), again.getMessage());
        assertFalse(EventTypes.get("pet_whistled").alwaysUrgent(), "先登记的那一行为准,后来的没改掉它");
    }

    @Test
    void aTypeWithoutAnIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EventTypes.register(EventTypes.event(" ", false)));
        assertThrows(IllegalArgumentException.class, () -> EventTypes.register(null));
    }

    @Test
    void aPluginEventTypeRendersAndClearsLikeTheBuiltIns() {
        EventTypes.register(EventTypes.event("accessory_changed", false));
        EventTypes.register(EventTypes.event("accessory_broke", true));
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);

        assertFalse(q.push("accessory_changed", "<event kind=\"accessory_changed\">戴上了戒指</event>", T0, false),
                "这种不恒急,发送方没标就不急");
        assertTrue(q.push("accessory_broke", "<event kind=\"accessory_broke\">护符碎了</event>", T0 + 1, false),
                "登记成恒急的,发送方怎么标都是急件");
        q.push(EventTypes.QUERY, "<query>你戴的什么</query>", T0 + 2, false);

        assertEquals(1, q.clearInterrupted(), "按停止只清主人的指令");
        assertEquals(List.of("accessory_changed", "accessory_broke"),
                q.entries().stream().map(EventQueue.Entry::type).toList(), "插件的事实留着");
        assertTrue(q.chatPreview().isEmpty(), "插件的事件不进聊天流");
        assertEquals(List.of("<events>\n<event kind=\"accessory_changed\">戴上了戒指</event>\n"
                        + "<event kind=\"accessory_broke\">护符碎了</event>\n</events>"),
                EventQueue.render(q.takeEntries(T0 + 2), T0 + 2), "和内置事件一样按时间排进 <events>");
    }

    @Test
    void theDropNoteIsADroppedEntryWhoseKindIsItsType() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE, 1);
        q.push(EventTypes.TASK_FINISHED, "<event>一</event>", T0, false);
        q.push(EventTypes.TASK_FINISHED, "<event>二</event>", T0, false);

        EventQueue.Entry note = q.takeEntries(T0).get(1);

        assertEquals(EventTypes.DROPPED, note.type());
        assertTrue(note.text().startsWith("<event kind=\"" + EventTypes.DROPPED + "\">"), note.text());
        assertNotNull(EventTypes.get(note.type()).toModel().apply(note.text()));
    }

    @Test
    void alwaysUrgentTypesAreUrgentWhateverTheSenderSays() {
        for (String id : new String[] {EventTypes.QUERY, EventTypes.GOAL, EventTypes.COMPACT, EventTypes.CLEAR,
                EventTypes.DEATH, EventTypes.HUNGRY, EventTypes.TIMER, EventTypes.WOKE}) {
            EventQueue q = new EventQueue(EventQueue.Journal.NONE);
            java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
            q.addUrgentListener(woken::incrementAndGet);

            assertTrue(q.push(id, "x", T0, false), id + ":发送方没标急,类型表说它恒急");

            assertTrue(q.entries().get(0).urgent(), id + ":条目上记的是生效后的急件");
            assertTrue(q.shouldDrain(T0, EventQueue.MAX_LEVEL), id + ":急件即熟");
            assertEquals(1, woken.get(), id + ":急件落地就叫醒等待者");
        }
    }

    @Test
    void otherTypesFollowTheSender() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);

        assertFalse(q.push(EventTypes.TASK_FINISHED, "<event>下雨了</event>", T0, false));
        assertFalse(q.hasUrgent(), "世界的事发送方没说急就不急");
        assertTrue(q.push(EventTypes.TASK_FINISHED, "<event>任务失败了</event>", T0, true));
        assertTrue(q.hasUrgent());
        assertFalse(q.push(EventTypes.QUERY, " ", T0, true), "空白不入队,也就谈不上急");
    }
}
