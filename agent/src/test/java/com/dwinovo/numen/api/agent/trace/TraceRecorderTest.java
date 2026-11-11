package com.dwinovo.numen.api.agent.trace;

import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.store.AppendLog;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一本按 session 起、按段滚的追加流：每段一个文件，一条一行，每条自己带着归属。 */
class TraceRecorderTest {

    /** 写不抛的直写:测试自己看文件,不必为写失败编一条路。 */
    private static Io io() {
        return Io.direct(e -> {
            throw e;
        });
    }

    /** 第 {@code segment} 段里的记录；那一段还没写过就是空表。 */
    private static List<AppendLog.Record> segment(Path dir, long session, int segment) {
        Path file = dir.resolve("trace").resolve(session + "-" + segment + ".jsonl");
        return new AppendLog(file, TraceRecorder.FORMAT, Io.direct(e -> { })).read().records();
    }

    /** 一条只有位置不同的身体记录。 */
    private static JsonObject body(int tick, double x) {
        JsonObject o = new JsonObject();
        o.addProperty("tick", tick);
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(64.0);
        pos.add(0.0);
        o.add("pos", pos);
        o.addProperty("yaw", 0.0);
        return o;
    }

    @Test
    void aTurnLandsInASegmentFileUnderTrace(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        JsonObject body = new JsonObject();
        body.addProperty("model", "zhipuai/glm-5.3-flash");

        trace.turn(42, 7, body);

        assertEquals(dir.resolve("trace").resolve(trace.session() + "-1.jsonl"), trace.file(),
                "第一段就是 -1");
        List<AppendLog.Record> records = segment(dir, trace.session(), 1);
        assertEquals(1, records.size());
        AppendLog.Record turn = records.get(0);
        assertEquals("turn", turn.type());
        assertEquals(42, turn.json().get("run").getAsLong());
        assertEquals(7, turn.json().get("n").getAsInt());
        assertEquals(trace.session(), turn.json().get("session").getAsLong(), "每条自己带着归属");
        assertEquals("zhipuai/glm-5.3-flash", turn.string("model"));
        assertTrue(turn.ts() > 0);
    }

    @Test
    void twoCompanionsDoNotShareASession(@TempDir Path dir) {
        TraceRecorder one = new TraceRecorder(dir.resolve("a"), io(), UUID.randomUUID());
        TraceRecorder two = new TraceRecorder(dir.resolve("b"), io(), UUID.randomUUID());

        assertNotEquals(one.session(), two.session());
    }

    @Test
    void theSameCompanionKeepsItsSessionAcrossRecorders(@TempDir Path dir) {
        UUID her = UUID.randomUUID();
        long first = new TraceRecorder(dir.resolve("a"), io(), her).session();
        long again = new TraceRecorder(dir.resolve("b"), io(), her).session();

        assertEquals(first, again, "重建大脑不该换文件:一次服务端运行只有一个 session");
    }

    @Test
    void aGenericEventIsJustAnotherTypeInTheSameFile(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        JsonObject body = new JsonObject();
        body.addProperty("kind", "giveup");

        trace.event("path", body);

        List<AppendLog.Record> records = segment(dir, trace.session(), 1);
        assertEquals("path", records.get(0).type());
        assertEquals("giveup", records.get(0).string("kind"));
    }

    @Test
    void everyRecordInOneFileCarriesTheSameSession(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        trace.turn(1, 1, new JsonObject());
        trace.event("body", new JsonObject());

        List<AppendLog.Record> records = segment(dir, trace.session(), 1);
        assertEquals(2, records.size());
        for (AppendLog.Record r : records) {
            assertEquals(trace.session(), r.json().get("session").getAsLong());
        }
    }

    @Test
    void theFileRollsOverOnceASegmentIsFull(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 3, 100);

        for (int i = 1; i <= 4; i++) {
            trace.turn(1, i, new JsonObject());
        }

        assertEquals(3, segment(dir, trace.session(), 1).size(), "第一段写满三条");
        assertEquals(1, segment(dir, trace.session(), 2).size(), "第四条滚到下一段");
        assertEquals(dir.resolve("trace").resolve(trace.session() + "-2.jsonl"), trace.file(),
                "file() 说的是正在写的那一段");
    }

    @Test
    void anUnchangedBodyIsNotWrittenTwice(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 100, 20);

        trace.body(body(1, 5.5));
        trace.body(body(2, 5.5));
        trace.body(body(3, 5.5));

        // 前三条只有 tick 不同：同一份内容不写第二遍
        List<AppendLog.Record> records = segment(dir, trace.session(), 1);
        assertEquals(1, records.size(), "没动的那些刻不该各占一条: " + records);
        assertEquals(1, records.get(0).json().get("tick").getAsInt());
    }

    @Test
    void aBodyThatChangedInAnyFieldIsWritten(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 100, 20);

        trace.body(body(1, 5.5));
        trace.body(body(2, 5.6));
        trace.body(body(3, 5.6));
        trace.body(body(4, 5.5));

        assertEquals(3, segment(dir, trace.session(), 1).size(), "动了就写");
    }

    @Test
    void aBodyThatStaysPutLongEnoughLeavesAHeartbeat(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 100, 20);

        for (int tick = 1; tick <= 45; tick++) {
            trace.body(body(tick, 5.5));
        }

        assertEquals(List.of(1, 21, 41),
                segment(dir, trace.session(), 1).stream()
                        .map(r -> r.json().get("tick").getAsInt()).toList(),
                "不动也要留心跳：否则读者只知道'上次动过'，不知道她还在");
    }

    @Test
    void theHeartbeatStartsOverOnceSheMovesAgain(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 100, 20);

        for (int tick = 1; tick <= 25; tick++) {
            trace.body(body(tick, 5.5));
        }
        trace.body(body(26, 6.0));
        for (int tick = 27; tick <= 45; tick++) {
            trace.body(body(tick, 6.0));
        }

        assertEquals(List.of(1, 21, 26),
                segment(dir, trace.session(), 1).stream()
                        .map(r -> r.json().get("tick").getAsInt()).toList(),
                "动过之后重新起算：26 那条是新的内容，之后到 45 还没再满 20 刻");
    }

    /** 文件里最后那条 turn。 */
    private static com.google.gson.JsonObject lastTurn(Path dir, TraceRecorder trace) {
        List<AppendLog.Record> turns = segment(dir, trace.session(), 1).stream()
                .filter(r -> "turn".equals(r.type())).toList();
        return turns.get(turns.size() - 1).json();
    }

    @Test
    void theSecondTurnCarriesTheFirstOnesBill(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        trace.on(new LoopEvent.TurnStarted(1, false));
        trace.on(new LoopEvent.ModelUsed(new Usage(100, 40, 0, 0), LoopEvent.Purpose.TURN));
        trace.on(new LoopEvent.AssistantMessage(1, new AssistantTurn("",
                List.of(new LlmToolCall("a", "lua", "{}"), new LlmToolCall("b", "dig", "{}")), null)));

        trace.turn(1, 2, new JsonObject());

        com.google.gson.JsonObject last = lastTurn(dir, trace).getAsJsonObject("lastTurn");
        assertEquals(40, last.get("out").getAsLong(), "输出 token 是空转的第一个信号");
        assertEquals(100, last.get("in").getAsLong());
        assertEquals(2, last.get("calls").getAsInt(), "输出大而 calls 是 1,就是空转");
        assertTrue(last.get("ms").getAsLong() >= 0);
    }

    @Test
    void theFirstTurnOfARunHasNoBillYet(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());

        trace.turn(1, 1, new JsonObject());

        assertFalse(lastTurn(dir, trace).has("lastTurn"), "第一轮前面没有轮");
    }

    @Test
    void aNewRunDoesNotCarryTheOldRunsBill(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        trace.on(new LoopEvent.TurnStarted(1, false));
        trace.on(new LoopEvent.ModelUsed(new Usage(1, 2, 0, 0), LoopEvent.Purpose.TURN));
        trace.on(new LoopEvent.RunEnded(1, RunEnd.DONE));
        trace.on(new LoopEvent.TurnStarted(2, false));
        trace.on(new LoopEvent.ModelUsed(new Usage(3, 4, 0, 0), LoopEvent.Purpose.TURN));

        trace.turn(2, 1, new JsonObject());

        assertFalse(lastTurn(dir, trace).has("lastTurn"), "换 run 了,上一轮的账不再算这一轮的");
    }

    @Test
    void compressionAndGoalCallsAreNotCounted(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID());
        trace.on(new LoopEvent.TurnStarted(1, false));
        trace.on(new LoopEvent.ModelUsed(new Usage(9, 9, 0, 0), LoopEvent.Purpose.COMPACT));

        trace.turn(1, 2, new JsonObject());

        assertFalse(lastTurn(dir, trace).has("lastTurn"), "压缩不动身体,不该混进这一轮的账");
    }

    @Test
    void aRolledOverSegmentStillBelongsToTheSameSession(@TempDir Path dir) {
        TraceRecorder trace = new TraceRecorder(dir, io(), UUID.randomUUID(), 2, 100);
        for (int i = 1; i <= 5; i++) {
            trace.turn(1, i, new JsonObject());
        }

        for (int s = 1; s <= 3; s++) {
            for (AppendLog.Record r : segment(dir, trace.session(), s)) {
                assertEquals(trace.session(), r.json().get("session").getAsLong(),
                        "分段不改归属:第 " + s + " 段那条对不上");
            }
        }
    }
}
