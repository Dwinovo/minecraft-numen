package com.dwinovo.numen.program;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 反向请求等答复的地方:只有被问的那位答得了、答一次;撤掉的不再认;断线的主人名下的都以失败结束。 */
class AnswerTableTest {

    private static ClientTransport.Answer answer(String reply) {
        return new ClientTransport.Answer(reply, null);
    }

    @Test
    void onlyTheOwnerAskedCanAnswerAndOnlyOnce() {
        AnswerTable table = new AnswerTable();
        UUID owner = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", owner, a -> got.add(a.reply()));

        table.complete("c1", UUID.randomUUID(), answer("stranger"));
        table.complete("c1", owner, answer("first"));
        table.complete("c1", owner, answer("second"));

        assertEquals(List.of("first"), got);
    }

    @Test
    void aCancelledRequestIsNotAnsweredAndTheOthersAre() {
        AnswerTable table = new AnswerTable();
        UUID owner = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", owner, a -> got.add("c1:" + a.reply()));
        table.expect("c2", owner, a -> got.add("c2:" + a.reply()));

        table.cancel("c1");
        table.cancel("never-asked");
        table.complete("c1", owner, answer("late"));
        table.complete("c2", owner, answer("in time"));

        assertEquals(List.of("c2:in time"), got);
    }

    @Test
    void anOwnerWhoLeavesFailsWhatWaitedOnHimAndNotOthers() {
        AnswerTable table = new AnswerTable();
        UUID leaver = UUID.randomUUID();
        UUID stayer = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        table.expect("c1", leaver, a -> got.add("c1:" + a.reply()));
        table.expect("c2", stayer, a -> got.add("c2:" + a.reply()));

        table.failOwner(leaver, id -> answer("gone " + id));
        table.complete("c2", stayer, answer("still here"));

        assertEquals(List.of("c1:gone c1", "c2:still here"), got);
    }
}
