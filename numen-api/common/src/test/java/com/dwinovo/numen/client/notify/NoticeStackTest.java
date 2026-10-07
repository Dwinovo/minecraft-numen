package com.dwinovo.numen.client.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoticeStackTest {

    private static final float STEP = 40f;

    private static List<String> live(NoticeStack<String> s, long now) {
        return s.placed(now).stream().filter(NoticeStack.Placed::live).map(NoticeStack.Placed::payload).toList();
    }

    @Test
    void aSecondLineFromTheSameConversationReplacesTheCardInsteadOfAddingOne() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "hi", 0);
        s.push("a", "still me", 100);
        assertEquals(List.of("still me"), live(s, 100));
    }

    @Test
    void theNewestSitsAtTheBottomAndTheOlderOnesSlideUpOneStep() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "1", 0);
        s.push("b", "2", 1000);
        var mid = s.placed(1000 + NoticeStack.SHIFT_MS / 2);
        assertEquals(STEP / 2, mid.get(0).lift(), 0.5f);
        var settled = s.placed(1000 + NoticeStack.SHIFT_MS);
        assertEquals(STEP, settled.get(0).lift(), 0.01f);
        assertEquals(0f, settled.get(1).lift(), 0.01f);
    }

    @Test
    void beyondTheLimitTheyQueueAndMoveUpWhenOneIsDismissed() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        for (int i = 0; i < NoticeStack.MAX_SHOWN + 1; i++) s.push("c" + i, "m" + i, 0);
        assertEquals(NoticeStack.MAX_SHOWN, live(s, 0).size());
        s.dismiss("c0", 10);
        assertEquals(List.of("m1", "m2", "m3"), live(s, 10));
    }

    @Test
    void aQueuedConversationIsMergedToo() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        for (int i = 0; i < NoticeStack.MAX_SHOWN; i++) s.push("c" + i, "m" + i, 0);
        s.push("late", "first", 0);
        s.push("late", "second", 0);
        s.dismiss("c0", 10);
        assertEquals(List.of("m1", "m2", "second"), live(s, 10));
    }

    @Test
    void itFadesInWaitsThenSlowlyFadesOutAndGoes() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "hi", 0);
        assertEquals(0f, s.placed(0).get(0).opacity(), 0.01f);
        assertEquals(1f, s.placed(NoticeStack.FADE_MS).get(0).opacity(), 0.01f);
        long hideAt = NoticeStack.WAIT_MS;
        s.advance(hideAt);
        // easeInCirc:淡到一半的时候几乎还是满的
        assertTrue(s.placed(hideAt + NoticeStack.SLOW_HIDE_MS / 2).get(0).opacity() > 0.8f);
        s.advance(hideAt + NoticeStack.SLOW_HIDE_MS);
        assertTrue(s.isIdle());
    }

    @Test
    void anotherLineRestartsTheWait() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "hi", 0);
        s.push("a", "again", 2000);
        s.advance(NoticeStack.WAIT_MS + 100);
        assertEquals(1f, s.placed(NoticeStack.WAIT_MS + 200).get(0).opacity(), 0.01f);
    }

    @Test
    void hoveringHoldsThemAndLeavingStartsTheFadeAtOnce() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "hi", 0);
        s.hover(true, 500);
        s.advance(60_000);
        assertEquals(1f, s.placed(60_000).get(0).opacity(), 0.01f);
        s.hover(false, 60_000);
        s.advance(60_000 + NoticeStack.SLOW_HIDE_MS);
        assertTrue(s.isIdle());
    }

    @Test
    void aDismissedCardStopsTakingASlotAndFadesOutFast() {
        NoticeStack<String> s = new NoticeStack<>(STEP);
        s.push("a", "1", 0);
        s.push("b", "2", 0);
        s.dismiss("b", 1000);
        var now = s.placed(1000);
        assertFalse(now.get(1).live());
        // 剩下那张往下补到底
        assertEquals(0f, s.placed(1000 + NoticeStack.SHIFT_MS).get(0).lift(), 0.01f);
        s.advance(1000 + NoticeStack.FADE_MS);
        assertEquals(List.of("1"), s.placed(1000 + NoticeStack.FADE_MS).stream().map(NoticeStack.Placed::payload).toList());
    }
}
