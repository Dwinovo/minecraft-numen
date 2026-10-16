package com.dwinovo.numen.api.agent.inbox;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外接大脑的长等待:有会叫醒她的事立刻返回,没有就停靠到调用方的期限,捎带的跟着走、只交一遍。 */
class ExternalWaitTest {

    private static final long T0 = 1_000_000L;

    private static EventQueue fresh() {
        return new EventQueue(EventQueue.Journal.NONE);
    }

    private static ExternalWait.Parked park(EventQueue q) {
        return ExternalWait.park(q, () -> T0);
    }

    @Test
    void returnsAtOnceWhenSomethingWasAlreadyWaiting() throws Exception {
        EventQueue q = fresh();
        q.push(EventTypes.TASK_FINISHED, "<event kind=\"task_finished\">挖完了</event>", T0, false);

        ExternalWait.Parked parked = park(q);

        assertTrue(parked.batch().isDone(), "不必是急件,会叫醒她的就立刻返回");
        assertTrue(parked.batch().get().contains("挖完了"));
        assertTrue(q.isEmpty());
    }

    @Test
    void parksUntilAWakingEntryLands() throws Exception {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);
        assertFalse(parked.batch().isDone(), "没事就一直等");

        q.push(EventTypes.QUERY, "<query>来一下</query>", T0, false);

        assertTrue(parked.batch().isDone());
        assertEquals("<query>来一下</query>", parked.batch().get());
        assertTrue(q.isEmpty());
    }

    @Test
    void ambientDoesNotWakeButRidesAlongWithTheNextWake() throws Exception {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);

        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 在吗</event>", T0, false);
        assertThrows(TimeoutException.class, () -> parked.batch().get(50, TimeUnit.MILLISECONDS),
                "捎带的不单独叫醒;没有别的事就等到调用方的期限");

        q.push(EventTypes.QUERY, "<query>来一下</query>", T0 + 1, false);

        String batch = parked.batch().get();
        assertTrue(batch.contains("在吗") && batch.contains("来一下"), "被叫醒时,捎带的一起交");
    }

    @Test
    void theDeadlineSweepDeliversRideAlongsAndNothingIsDeliveredTwice() {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);
        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 在吗</event>", T0, false);
        parked.cancel().get();

        String swept = q.takeRenderedText(T0);
        assertTrue(swept.contains("在吗"), "到点清扫有什么给什么");
        assertNull(q.takeRenderedText(T0), "取走即不再交");
    }

    @Test
    void aWaiterWhoLostTheRaceKeepsWaiting() throws Exception {
        EventQueue q = fresh();
        ExternalWait.Parked first = park(q);
        ExternalWait.Parked second = park(q);

        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertEquals("<query>a</query>", first.batch().get(), "同一份货只交给一个取件人");
        assertFalse(second.batch().isDone(), "货被拿走了,后一个继续等,不重复交");
    }

    @Test
    void cancelWithdrawsTheWaiter() {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);
        parked.cancel().get();

        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertFalse(parked.batch().isDone());
        assertEquals("<query>a</query>", q.takeRenderedText(T0), "货留在队里给下一个取件人");
    }

    @Test
    void aDeadlineThatLandsOnTheWakeStillGetsTheBatch() {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);

        // 调用方的期限到了、还没来得及撤销的当口,叫醒先把批次取走了
        q.push(EventTypes.QUERY, "<query>来一下</query>", T0, false);
        String late = parked.cancel().get();

        assertEquals("<query>来一下</query>", late, "取走的批次由撤销交回,不会取件了却没人收");
        assertTrue(q.isEmpty());
    }

    @Test
    void cancelBeforeAnyWakeLeavesTheEventInTheQueue() {
        EventQueue q = fresh();
        ExternalWait.Parked parked = park(q);

        assertNull(parked.cancel().get());
        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertNull(parked.cancel().get(), "撤销之后叫醒不再取件");
        assertEquals("<query>a</query>", q.takeRenderedText(T0));
    }

    @Test
    void parkingOnAnotherThreadCanBeWithdrawnBeforeTheRegistrationRuns() {
        EventQueue q = fresh();
        java.util.List<Runnable> pending = new java.util.ArrayList<>();
        ExternalWait.Parked parked = ExternalWait.parkOn(pending::add, () -> park(q));

        assertNull(parked.cancel().get());
        pending.forEach(Runnable::run);
        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertFalse(parked.batch().isDone(), "撤销在先,登记就不再做");
        assertEquals("<query>a</query>", q.takeRenderedText(T0), "货没被一个没人读的等待者拿走");
    }

    @Test
    void parkingOnAnotherThreadDeliversAfterTheRegistration() throws Exception {
        EventQueue q = fresh();
        java.util.List<Runnable> pending = new java.util.ArrayList<>();
        ExternalWait.Parked parked = ExternalWait.parkOn(pending::add, () -> park(q));
        pending.forEach(Runnable::run);

        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertEquals("<query>a</query>", parked.batch().get());
        assertEquals("<query>a</query>", parked.cancel().get());
    }

    @Test
    void controlEntriesNeitherWakeNorBlockText() throws Exception {
        EventQueue q = fresh();
        q.push(EventTypes.CLEAR, "清空上下文", T0, false);
        ExternalWait.Parked parked = park(q);
        assertFalse(parked.batch().isDone());

        q.push(EventTypes.QUERY, "<query>a</query>", T0, false);

        assertEquals("<query>a</query>", parked.batch().get());
        assertEquals(1, q.size(), "控制条目留着等内脑");
    }
}
