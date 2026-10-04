package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受理 = 这件活此刻真能开始。准备位只管一件事:准备有了结论才了结那次调用,恰好一次;被拒的调用碰不到她手上的活。
 *
 * <p>"她手上的活"在这里是一个槽的替身({@link #slot}):结论就绪才把新活换进去,和 {@link TaskDispatch} 受理时换槽同一个
 * 时机;被拒只回一条错误。
 */
class PreparingTest {

    /** 她手上的活:受理时换成新的那件。 */
    private final AtomicReference<String> slot = new AtomicReference<>("digging");
    /** 每次调用拿到的结果,按到达的先后。 */
    private final List<String> replies = new ArrayList<>();

    /** 一次调用:就绪就换槽、回"已受理",不成就回错误——和 TaskDispatch 的受理同一个形状。 */
    private Preparing.Call call(String job, Preparation preparation) {
        return new Preparing.Call(preparation, readiness -> {
            if (readiness.ready()) {
                slot.set(job);
                replies.add(job + " accepted");
            } else {
                replies.add(job + " refused: " + readiness.refusal().message());
            }
        });
    }

    /** 一次在后台搜索的准备:结论由用例交进来,之前每刻都说还没有。 */
    private static final class Searching implements Preparation {
        Preparation.Readiness answer;
        boolean cancelled;
        int polls;

        @Override
        public Preparation.Readiness poll() {
            polls++;
            return answer;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    void aCallThatNeedsNoSearchIsAnsweredOnTheSpot() {
        Preparing preparing = new Preparing();
        preparing.begin(call("walk", Preparation.READY));
        assertEquals(List.of("walk accepted"), replies);
        assertEquals("walk", slot.get());
    }

    @Test
    void aSearchingCallIsAnsweredWhenTheSearchConcludesAndOnlyOnce() {
        Preparing preparing = new Preparing();
        Searching search = new Searching();
        preparing.begin(call("walk", search));
        preparing.tick();
        preparing.tick();
        assertTrue(replies.isEmpty(), "answered before the search concluded: " + replies);
        assertEquals("digging", slot.get(), "the work in hand was replaced before the new one was accepted");

        search.answer = Preparation.Readiness.READY;
        preparing.tick();
        int polled = search.polls;
        preparing.tick();
        preparing.tick();
        assertEquals(List.of("walk accepted"), replies);
        assertEquals("walk", slot.get());
        assertEquals(polled, search.polls, "a concluded preparation was asked again");
    }

    @Test
    void aRefusedCallLeavesTheWorkInHandAlone() {
        Preparing preparing = new Preparing();
        Searching search = new Searching();
        preparing.begin(call("walk", search));
        search.answer = Preparation.Readiness.refused(TaskResult.fail("found no path"));
        preparing.tick();
        assertEquals(List.of("walk refused: found no path"), replies);
        assertEquals("digging", slot.get(), "a refused call interrupted the work in hand");
    }

    @Test
    void aFailedPreconditionIsRefusedOnTheSpot() {
        Preparing preparing = new Preparing();
        preparing.begin(call("fish", Preparation.refused(TaskResult.fail("fish needs a fishing rod in inventory"))));
        assertEquals(List.of("fish refused: fish needs a fishing rod in inventory"), replies);
        assertEquals("digging", slot.get());
    }

    @Test
    void aNewerCallTakesThePlaceOfTheOneStillBeingPrepared() {
        Preparing preparing = new Preparing();
        Searching first = new Searching();
        Searching second = new Searching();
        preparing.begin(call("walk", first));
        preparing.begin(call("dig", second));
        assertTrue(first.cancelled, "the search of the replaced call kept running");
        assertEquals(List.of("walk refused: not started: a newer body action came in before it was ready"), replies);
        assertEquals("digging", slot.get());

        first.answer = Preparation.Readiness.READY;
        second.answer = Preparation.Readiness.READY;
        preparing.tick();
        assertEquals("dig", slot.get());
        assertEquals(2, replies.size(), "a withdrawn call was answered twice");
    }

    @Test
    void stoppingWithdrawsTheCallBeingPreparedAndSaysWhy() {
        Preparing preparing = new Preparing();
        Searching search = new Searching();
        preparing.begin(call("walk", search));
        preparing.withdraw(TaskRecord.StopCause.OWNER.words());
        assertTrue(search.cancelled);
        assertEquals(List.of("walk refused: not started: the owner pressed Stop"), replies);
        preparing.tick();
        assertEquals(1, replies.size());
    }

    @Test
    void droppingCancelsWithoutAnAnswer() {
        Preparing preparing = new Preparing();
        Searching search = new Searching();
        preparing.begin(call("walk", search));
        preparing.drop();
        search.answer = Preparation.Readiness.READY;
        preparing.tick();
        assertTrue(search.cancelled);
        assertTrue(replies.isEmpty(), "a dropped call was answered: " + replies);
        assertFalse(slot.get().equals("walk"));
    }

    @Test
    void withdrawingWhenNothingIsPreparedDoesNothing() {
        Preparing preparing = new Preparing();
        preparing.withdraw("the owner pressed Stop");
        preparing.drop();
        preparing.tick();
        assertTrue(replies.isEmpty());
    }
}
