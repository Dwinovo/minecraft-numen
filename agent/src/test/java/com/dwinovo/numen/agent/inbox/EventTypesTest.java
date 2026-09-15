package com.dwinovo.numen.agent.inbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型表是"这类条目怎么处理"的唯一答案。循环不再按类型字符串判断什么时候交给大脑、是不是急件,
 * 所以表里每一格都得是对的——这里一格一格钉住。
 */
class EventTypesTest {

    private static final long T0 = 1_000_000L;

    private static void assertRow(String id, EventTypes.Delivery delivery, boolean alwaysUrgent,
                                  boolean clearedByInterrupt, boolean fromOwner) {
        EventTypes.Type t = EventTypes.get(id);
        assertEquals(id, t.id());
        assertEquals(delivery, t.delivery(), id + " 的投递方式");
        assertEquals(alwaysUrgent, t.alwaysUrgent(), id + " 恒不恒急");
        assertEquals(clearedByInterrupt, t.clearedByInterrupt(), id + " 打断时清不清");
        assertEquals(fromOwner, t.fromOwner(), id + " 算不算主人说的");
    }

    @Test
    void builtInTypesAreRegisteredAsTheTableSays() {
        assertRow(EventTypes.QUERY, EventTypes.Delivery.STEER, true, true, true);
        assertRow(EventTypes.EVENT, EventTypes.Delivery.STEER, false, false, false);
        assertRow(EventTypes.GOAL, EventTypes.Delivery.FOLLOW_UP, true, true, true);
        assertRow(EventTypes.COMPACT, EventTypes.Delivery.CONTROL, true, true, true);
        assertRow(EventTypes.CLEAR, EventTypes.Delivery.CONTROL, true, true, true);
    }

    @Test
    void anUnregisteredTypeIsPlainTextTheSenderDecidesOn() {
        EventTypes.Type t = EventTypes.get("谁也没登记过的类型");
        assertEquals(EventTypes.Delivery.STEER, t.delivery(), "没登记的当普通文本交给模型,不当控制命令吞掉");
        assertFalse(t.alwaysUrgent());
    }

    @Test
    void alwaysUrgentTypesAreUrgentWhateverTheSenderSays() {
        for (String id : new String[] {EventTypes.QUERY, EventTypes.GOAL, EventTypes.COMPACT, EventTypes.CLEAR}) {
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

        assertFalse(q.push(EventTypes.EVENT, "<event>下雨了</event>", T0, false));
        assertFalse(q.hasUrgent(), "世界的事发送方没说急就不急");
        assertTrue(q.push(EventTypes.EVENT, "<event>任务失败了</event>", T0, true));
        assertTrue(q.hasUrgent());
        assertFalse(q.push(EventTypes.QUERY, " ", T0, true), "空白不入队,也就谈不上急");
    }
}
