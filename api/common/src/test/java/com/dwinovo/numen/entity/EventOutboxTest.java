package com.dwinovo.numen.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人离线期间攒下的事件。
 *
 * <p>这个类存在的唯一理由:大脑跑在主人的客户端上,他一下线,收件箱就不存在了,
 * 而她的身体还在服务器里干活。从前这些事件一律直接丢——"我帮你把矿挖完了"
 * 这件最值得说的事,恰好最容易丢。
 */
class EventOutboxTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static EventOutbox roundTrip(EventOutbox box) {
        return EventOutbox.load(box.save(new CompoundTag(), null), null);
    }

    @Test
    void pendingEventsSurviveAServerRestart() {
        // 多人服务器重启是常事;纯内存的话最有价值的长时段叙事恰好最容易丢
        EventOutbox box = new EventOutbox();
        box.put(A, "<event kind=\"body_log\" day=\"3\">吃了个面包</event>", false);
        box.put(A, "<event kind=\"task_finished\" status=\"done\">矿挖完了</event>", true);

        EventOutbox back = roundTrip(box);

        assertEquals(2, back.peek(A).pending().size());
        assertEquals("<event kind=\"body_log\" day=\"3\">吃了个面包</event>",
                back.peek(A).pending().get(0).xml());
        assertTrue(back.peek(A).pending().get(1).urgent(), "补发时它仍然该立刻开一轮");
    }

    @Test
    void takeHandsOverEverythingAndEmpties() {
        EventOutbox box = new EventOutbox();
        box.put(A, "<event>一</event>", false);
        box.put(A, "<event>二</event>", false);

        EventOutbox.Box taken = box.take(A);

        assertEquals(2, taken.pending().size());
        assertTrue(box.peek(A).pending().isEmpty(), "补发过就不能再发一次");
    }

    @Test
    void takingFromAnEmptyBoxIsHarmless() {
        assertTrue(new EventOutbox().take(A).pending().isEmpty());
    }

    @Test
    void boxesAreIsolatedPerCompanion() {
        EventOutbox box = new EventOutbox();
        box.put(A, "<event>甲的</event>", false);
        box.put(B, "<event>乙的</event>", false);

        box.take(A);

        assertEquals(1, box.peek(B).pending().size(), "不许殃及别人");
    }

    // ---- 上限 ----

    @Test
    void overflowDropsTheOldestAndCountsIt() {
        // 主人离线一周不能回来收几千条,但丢弃必须留账——他得知道看到的是全部还是残片
        EventOutbox box = new EventOutbox();
        for (int i = 0; i < EventOutbox.MAX_PER_COMPANION + 7; i++) {
            box.put(A, "<event>第" + i + "件</event>", false);
        }

        EventOutbox.Box out = box.take(A);

        assertEquals(EventOutbox.MAX_PER_COMPANION, out.pending().size());
        assertEquals(7, out.dropped(), "丢了几条要说得出来");
        assertEquals("<event>第7件</event>", out.pending().get(0).xml(), "丢的是最老的");
    }

    @Test
    void dropCountSurvivesARestartToo() {
        EventOutbox box = new EventOutbox();
        for (int i = 0; i < EventOutbox.MAX_PER_COMPANION + 3; i++) {
            box.put(A, "<event>x</event>", false);
        }
        assertEquals(3, roundTrip(box).peek(A).dropped());
    }

    // ---- 生命周期 ----

    @Test
    void dismissedCompanionTakesHerBoxWithHer() {
        EventOutbox box = new EventOutbox();
        box.put(A, "<event>没人会再收</event>", false);

        box.forget(A);

        assertTrue(box.peek(A).pending().isEmpty());
        assertTrue(roundTrip(box).peek(A).pending().isEmpty());
    }

    @Test
    void forgettingAnUnknownCompanionIsHarmless() {
        new EventOutbox().forget(UUID.randomUUID());
    }

    @Test
    void garbageTagDegradesToEmpty() {
        assertTrue(EventOutbox.load(new CompoundTag(), null).peek(A).pending().isEmpty());
    }
}
