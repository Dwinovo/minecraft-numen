package com.dwinovo.numen.entity;

import com.dwinovo.numen.event.EventQueue;
import com.dwinovo.numen.event.EventTypes;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人离线期间攒下的输入——服务端这一侧的队列。
 *
 * <p>它存在的唯一理由:大脑跑在主人的客户端上,他一下线那个队列就不存在了,而她的
 * 身体还在服务器里干活。这些事得有地方躺着等他回来——"我帮你把矿挖完了"是最值得
 * 说的一件,也是最容易丢的一件。
 */
class EventOutboxTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long T0 = 1_000_000L;

    private static EventOutbox roundTrip(EventOutbox box) {
        return EventOutbox.load(box.save(new CompoundTag()));
    }

    @Test
    void pendingInputSurvivesAServerRestart() {
        // 多人服务器重启是常事;纯内存的话最有价值的长时段叙事恰好最容易丢
        EventOutbox box = new EventOutbox();
        box.put(A, EventTypes.EVENT, "<event kind=\"body_log\" day=\"3\">吃了个面包</event>", T0, false);
        box.put(A, EventTypes.EVENT, "<event kind=\"task_finished\">矿挖完了</event>", T0 + 5, true);

        EventOutbox back = roundTrip(box);

        assertEquals(2, back.peek(A).size());
        assertTrue(back.peek(A).hasUrgent(), "补发时它仍然该立刻开一轮");
    }

    @Test
    void takeHandsOverRawEntriesNotRenderedText() {
        // 渲染会把类型和时间戳压成一个字符串,客户端就没法知道"这是三小时前的事"了
        EventOutbox box = new EventOutbox();
        box.put(A, EventTypes.EVENT, "<event>一</event>", T0, true);

        List<EventQueue.Entry> taken = box.take(A, T0);

        assertEquals(1, taken.size());
        assertEquals(EventTypes.EVENT, taken.get(0).type());
        assertEquals(T0, taken.get(0).ts(), "事发时刻必须原样送到客户端");
        assertTrue(taken.get(0).urgent());
        assertTrue(box.peek(A).isEmpty(), "补发过就不能再发一次");
    }

    @Test
    void takingFromAnEmptyBoxIsHarmless() {
        assertTrue(new EventOutbox().take(A, T0).isEmpty());
    }

    @Test
    void boxesAreIsolatedPerCompanion() {
        EventOutbox box = new EventOutbox();
        box.put(A, EventTypes.EVENT, "<event>甲的</event>", T0, false);
        box.put(B, EventTypes.EVENT, "<event>乙的</event>", T0, false);

        box.take(A, T0);

        assertEquals(1, box.peek(B).size(), "不许殃及别人");
    }

    @Test
    void overflowDropsTheOldestAndSaysSoOnReplay() {
        // 主人离线一周不能回来收几千条,但丢弃必须留账
        EventOutbox box = new EventOutbox();
        int over = 7;
        for (int i = 0; i < EventQueue.DEFAULT_CAP + over; i++) {
            box.put(A, EventTypes.EVENT, "<event>第" + i + "件</event>", T0, false);
        }

        List<EventQueue.Entry> taken = box.take(A, T0);

        assertEquals(EventQueue.DEFAULT_CAP + 1, taken.size(), "上限内的 + 一条丢弃说明");
        assertTrue(taken.get(0).text().contains("第" + over + "件"), "丢的是最老的");
        assertTrue(taken.get(taken.size() - 1).text().contains(over + " 件事"),
                "丢了几条要说得出来:" + taken.get(taken.size() - 1).text());
    }

    @Test
    void dismissedCompanionTakesHerBoxWithHer() {
        EventOutbox box = new EventOutbox();
        box.put(A, EventTypes.EVENT, "<event>没人会再收</event>", T0, false);

        box.forget(A);

        assertTrue(box.peek(A).isEmpty());
        assertTrue(roundTrip(box).peek(A).isEmpty());
    }

    @Test
    void forgettingAnUnknownCompanionIsHarmless() {
        new EventOutbox().forget(UUID.randomUUID());
    }

    @Test
    void garbageTagDegradesToEmpty() {
        assertTrue(EventOutbox.load(new CompoundTag()).peek(A).isEmpty());
    }
}
