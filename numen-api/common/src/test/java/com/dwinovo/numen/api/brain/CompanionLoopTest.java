package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.loop.Hold;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端大脑接在宿主上的那几处({@link BrainFixture} 是无头的宿主):主人不在场、她死着、主人按停止时不开 run,条件解除才把攒着的一起处理。
 * 内核本身的规矩在 {@code AgentLoopTest};这里验的是 {@link CompanionLoop} 把宿主给的这些接对了。
 */
class CompanionLoopTest {

    /** 主人不在线时事件照收、不开 run;他回来,攒着的一起处理。 */
    @Test
    void anAwayOwnerLeavesTheEventsWaitingInHerInbox() throws IOException {
        BrainFixture her = new BrainFixture();
        her.ownerOnline = false;
        her.mind.say("欢迎回来");

        // 和服务端事件同一个构造口,急件(任务收尾)
        her.loop.push(java.util.List.of(com.dwinovo.numen.api.event.NumenEvents.entry(6000L, EventTypes.TASK_FINISHED, null,
                "矿挖完了", System.currentTimeMillis(), true)));
        her.tick(5);
        assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TASK_FINISHED) && e.id() != null),
                "the event did not reach her inbox: " + her.loop.inbox().entries());
        assertEquals(Hold.AWAY, her.loop.status().hold());
        assertTrue(her.mind.asked.isEmpty(), "she thought while her owner was away");

        her.ownerOnline = true;
        her.tick(5);
        assertEquals(1, her.mind.asked.size(), "she did not take up the waiting event");
        assertNull(her.loop.status().hold());
    }

    /** 死着时不开 run;重生时死亡的叙事随攒着的输入一起走。 */
    @Test
    void deathFreezesTheLoopAndTheRespawnTellsHerHowSheDied() throws IOException {
        BrainFixture her = new BrainFixture();
        her.mind.say("我回来了");

        her.loop.onDied("摔死了", "你死在 minecraft:overworld。");
        her.loop.submitPrompt("在吗");
        her.tick(2);
        assertEquals(Hold.DEAD, her.loop.status().hold());
        assertTrue(her.mind.asked.isEmpty(), "she thought while dead");

        her.loop.onRespawned();
        her.tick(5);
        assertEquals(1, her.mind.asked.size(), "the respawn did not wake her");
        String told = her.toldIn(her.mind.asked.get(0));
        assertTrue(told.contains("摔死了") && told.contains("在吗"), told);
    }

    /**
     * 主人给她定的定时到点只送一次:到期后她听到这句话,之后一个间隔之内不再有;她的大脑换新(重载存档)之后也不重发——下次到期刻
     * 是落了盘的绝对刻。游戏刻是宿主给的,这里一刻一刻拨。
     */
    @Test
    void aScheduleIsHeardOnceAtItsTimeAndAReloadDoesNotRepeatIt() throws IOException {
        BrainFixture her = new BrainFixture();
        her.mind.say("好").say("好").say("好");
        her.loop.putSchedule(null, "看看炉子", OwnerSchedules.MIN_EVERY_TICKS);
        her.tick((int) OwnerSchedules.MIN_EVERY_TICKS - 1);
        assertEquals(0, her.scheduledHeard(), "heard before its time");
        her.tick(2);
        assertEquals(1, her.scheduledHeard(), "not heard at its time");
        her.tick(20);
        assertEquals(1, her.scheduledHeard(), "heard more than once");

        BrainFixture again = her.reloaded();
        assertEquals(1, again.loop.schedules().size());
        assertEquals("看看炉子", again.loop.schedules().get(0).text());
        again.tick(100);
        assertEquals(1, again.scheduledHeard(), "the reloaded brain heard the schedule again");
    }

    /** 主人不在线时到期的定时不送、不丢:一直留到他回来,回来后只收到一句。 */
    @Test
    void aScheduleDueWhileTheOwnerIsAwayIsHeardOnceWhenHeReturns() throws IOException {
        BrainFixture her = new BrainFixture();
        her.ownerOnline = false;
        her.mind.say("好").say("好").say("好");
        her.loop.putSchedule(null, "早点回来", OwnerSchedules.MIN_EVERY_TICKS);
        her.tick((int) OwnerSchedules.MIN_EVERY_TICKS + 40);
        assertEquals(0, her.scheduledHeard());
        assertTrue(her.loop.inbox().entries().stream().noneMatch(e -> com.dwinovo.numen.api.agent.llm.ConvoLog.scheduled(e.text())),
                "delivered while the owner was away: " + her.loop.inbox().entries());

        her.ownerOnline = true;
        her.tick(100);
        assertEquals(1, her.scheduledHeard(), "the owner back did not hear exactly one schedule");
    }

    /** 主人按停止:在飞的回复作废,日志记下切断点,没有新的 run,直到主人再开口。 */
    @Test
    void stopVoidsTheReplyInFlightUntilTheOwnerSpeaksAgain() throws IOException {
        BrainFixture her = new BrainFixture();
        her.mind.holding = true;
        her.mind.say("这句不该被听到").say("好的");

        her.loop.submitPrompt("去挖矿");
        her.tick(1);
        assertEquals(1, her.mind.asked.size(), "she did not start thinking");
        her.loop.abort();
        her.mind.release();
        her.tick(5);
        assertEquals(Hold.OWNER_STOP, her.loop.status().hold());
        assertTrue(her.transcript().stream().noneMatch(m -> m instanceof ConvoState.Msg.Assistant), her.transcript().toString());
        assertTrue(her.transcript().stream().anyMatch(m -> m instanceof ConvoState.Msg.Halt), her.transcript().toString());

        her.mind.holding = false;
        her.loop.submitPrompt("继续");
        her.tick(5);
        assertEquals(2, her.mind.asked.size(), "the owner's next word did not wake her");
        assertNull(her.loop.status().hold());
    }
}
