package com.dwinovo.numen.api.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void aFullBoxRefusesAndOnlyThatPlayersBoxIsFull() {
        Mailbox<String> box = new Mailbox<>(2);

        assertTrue(box.offer(A, "一"));
        assertTrue(box.offer(A, "二"));
        assertFalse(box.offer(A, "三"), "A 的信箱满了");
        assertTrue(box.offer(B, "甲"), "B 的信箱不受影响");
    }

    @Test
    void drainTakesAFewPerPlayerInOrderAndLeavesTheRestForTheNextTick() {
        Mailbox<String> box = new Mailbox<>(10);
        for (String s : List.of("一", "二", "三")) {
            box.offer(A, s);
        }
        box.offer(B, "甲");
        List<String> taken = new ArrayList<>();

        box.drain(2, (player, item) -> taken.add(player.equals(A) ? item : "B:" + item));
        assertEquals(List.of("一", "二", "B:甲").stream().sorted().toList(), taken.stream().sorted().toList());

        taken.clear();
        box.drain(2, (player, item) -> taken.add(item));
        assertEquals(List.of("三"), taken);
        assertTrue(box.offer(A, "四"), "取走的腾出了位置");
    }

    @Test
    void aPlayerWhoLeftTakesHisUnhandledWordsWithHim() {
        Mailbox<String> box = new Mailbox<>(10);
        box.offer(A, "一");

        box.drop(A);

        List<String> taken = new ArrayList<>();
        box.drain(8, (player, item) -> taken.add(item));
        assertTrue(taken.isEmpty());
    }
}
