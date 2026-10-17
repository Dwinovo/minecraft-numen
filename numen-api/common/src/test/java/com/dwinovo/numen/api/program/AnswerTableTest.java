package com.dwinovo.numen.api.program;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 反向请求等答复的地方:只有被问的那位答得了、答一次;撤掉的不再认;断线的主人名下的都以失败结束。 */
class AnswerTableTest {

    @Test
    void onlyTheOwnerAskedCanAnswerAndOnlyOnce() {
        AnswerTable table = new AnswerTable();
        UUID owner = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", owner, got::add);

        table.complete("c1", UUID.randomUUID(), "stranger");
        table.complete("c1", owner, "first");
        table.complete("c1", owner, "second");

        assertEquals(List.of("first"), got);
    }

    @Test
    void aCancelledRequestIsNotAnsweredAndTheOthersAre() {
        AnswerTable table = new AnswerTable();
        UUID owner = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", owner, reply -> got.add("c1:" + reply));
        table.expect("c2", owner, reply -> got.add("c2:" + reply));

        table.cancel("c1");
        table.cancel("never-asked");
        table.complete("c1", owner, "late");
        table.complete("c2", owner, "in time");

        assertEquals(List.of("c2:in time"), got);
    }

    @Test
    void anOwnerWhoLeavesFailsWhatWaitedOnHimAndNotOthers() {
        AnswerTable table = new AnswerTable();
        UUID leaver = UUID.randomUUID();
        UUID stayer = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", leaver, reply -> got.add("c1:" + reply));
        table.expect("c2", stayer, reply -> got.add("c2:" + reply));

        table.failOwner(leaver, id -> "gone " + id);
        table.complete("c2", stayer, "still here");

        assertEquals(List.of("c1:gone c1", "c2:still here"), got);
    }
}
