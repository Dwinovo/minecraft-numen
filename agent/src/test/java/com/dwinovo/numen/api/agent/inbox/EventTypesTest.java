package com.dwinovo.numen.api.agent.inbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型表是"这类条目怎么处理"的唯一答案:登记、不认识的类型怎么办、文本原样交给模型、急件与控制命令的约束。
 */
class EventTypesTest {

    private static final long T0 = 1_000_000L;

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
                EventQueue.render(q.takeEntries(T0 + 2, 0L)), "和内置事件一样按时间排进 <events>");
    }

    @Test
    void theDropNoteIsADroppedEntryWhoseKindIsItsType() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE, 1);
        q.push(EventTypes.TASK_FINISHED, "<event>一</event>", T0, false);
        q.push(EventTypes.TASK_FINISHED, "<event>二</event>", T0, false);

        EventQueue.Entry note = q.takeEntries(T0, 0L).get(1);

        assertEquals(EventTypes.DROPPED, note.type());
        assertTrue(note.text().startsWith("<event kind=\"" + EventTypes.DROPPED + "\" day=\"0\" t=\"06:00\">"), note.text());
        assertNotNull(EventTypes.get(note.type()).toModel().apply(note.text()));
    }

    @Test
    void alwaysUrgentTypesAreUrgentWhateverTheSenderSays() {
        for (String id : new String[] {EventTypes.QUERY, EventTypes.GOAL,
                EventTypes.DEATH, EventTypes.HUNGRY, EventTypes.TIMER, EventTypes.WOKE}) {
            EventQueue q = new EventQueue(EventQueue.Journal.NONE);
            java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
            q.addWakeListener(woken::incrementAndGet);

            assertTrue(q.push(id, "x", T0, false), id + ":发送方没标急,类型表说它恒急");

            assertTrue(q.entries().get(0).urgent(), id + ":条目上记的是生效后的急件");
            assertTrue(q.ripeness(T0, EventQueue.MAX_LEVEL).ripe(), id + ":急件即熟");
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

    // ---- 投递档的声明 ----

    /** 急件的意思是立刻叫醒她:不叫醒她的档登记成恒急,当场拒绝,而不是登记进去再悄悄不生效。 */
    @Test
    void aTypeThatDoesNotWakeCannotBeAlwaysUrgent() {
        for (EventTypes.Delivery d : EventTypes.Delivery.values()) {
            if (d.wakes()) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new EventTypes.Type("never_" + d, s -> s, s -> null, false, false, d, true), d.toString());
        }
    }

    /** 主人开口只认一处:来自主人、叫醒她、随下一次调用就走的条目。 */
    @Test
    void ownerWordsAreReadFromTheDeclarations() {
        assertTrue(EventTypes.get(EventTypes.QUERY).ownerWords());
        assertFalse(EventTypes.get(EventTypes.GOAL).ownerWords(), "续跑是她自己接着干");
        assertFalse(EventTypes.get(EventTypes.CLEAR).ownerWords(), "清空是按钮,不是话");
        assertFalse(EventTypes.get(EventTypes.COMPACT).ownerWords());
        assertFalse(EventTypes.get(EventTypes.TALK).ownerWords(), "旁听到的话不是主人对她说的");
        assertFalse(EventTypes.get(EventTypes.TASK_FINISHED).ownerWords());
    }
}
