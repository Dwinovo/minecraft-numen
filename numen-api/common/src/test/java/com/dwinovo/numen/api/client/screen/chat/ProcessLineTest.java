package com.dwinovo.numen.api.client.screen.chat;

import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.client.screen.chat.ProcessLine.Step;
import com.dwinovo.numen.script.ScriptEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLineTest {

    private static LlmToolCall lua(String id, String code) {
        return new LlmToolCall(id, ScriptEngine.IN_USE.toolName(), ScriptTool.args(code, "do it").toString());
    }

    private static Step done(String id, long start, long end) {
        return Step.call(lua(id, "return 1"), start, end, false, false, "ok");
    }

    @Test
    void aThoughtLastsFromThePreviousRecordToItsTurn() {
        assertEquals(3000, Step.thought("hm", 10_000, 13_000).millis(99_000));
    }

    @Test
    void aCallLastsFromItsTurnToItsResult() {
        assertEquals(2000, done("a", 13_000, 15_000).millis(99_000));
    }

    @Test
    void aRunningStepLastsUntilNow() {
        Step running = Step.call(lua("a", "x"), 13_000, 0, true, false, null);
        assertEquals(4500, running.millis(17_500));
        assertEquals(1200, Step.liveThought("hm", 16_300).millis(17_500));
    }

    @Test
    void recordsWithoutATimeAndCallsThatNeverGotAResultCountZero() {
        assertEquals(0, Step.thought("hm", 0, 0).millis(99_000));
        assertEquals(0, Step.call(lua("a", "x"), 13_000, 0, false, true, null).millis(99_000));
    }

    @Test
    void totalRunsFromTheFirstStartToTheLastEnd() {
        List<Step> steps = List.of(Step.thought("a", 10_000, 13_000), done("c1", 13_000, 15_000),
                Step.thought("b", 15_000, 17_000));
        assertEquals(7000, ProcessLine.totalMillis(steps, 99_000));
    }

    @Test
    void totalOfARunningProcessRunsToNow() {
        List<Step> steps = List.of(Step.thought("a", 10_000, 13_000),
                Step.call(lua("c1", "x"), 13_000, 0, true, false, null));
        assertEquals(9000, ProcessLine.totalMillis(steps, 19_000));
    }

    @Test
    void totalWithoutAFirstTimeIsZero() {
        assertEquals(0, ProcessLine.totalMillis(List.of(done("c1", 0, 0)), 19_000));
    }

    @Test
    void secondsFloor() {
        assertEquals(2, ProcessLine.seconds(2999));
        assertEquals(0, ProcessLine.seconds(999));
    }

    @Test
    void oneCallWithNoThoughtIsSingle() {
        assertTrue(ProcessLine.single(List.of(done("c1", 1, 2))));
        assertFalse(ProcessLine.single(List.of(Step.thought("a", 1, 2), done("c1", 2, 3))));
        assertFalse(ProcessLine.single(List.of(done("c1", 1, 2), done("c2", 2, 3))));
        assertFalse(ProcessLine.single(List.of(Step.thought("a", 1, 2))));
    }

    @Test
    void hasCallsTellsWorkedFromThought() {
        assertTrue(ProcessLine.hasCalls(List.of(Step.thought("a", 1, 2), done("c1", 2, 3))));
        assertFalse(ProcessLine.hasCalls(List.of(Step.thought("a", 1, 2))));
    }

    @Test
    void failureCountsOnlyWhenTheLastStepFailed() {
        Step bad = Step.call(lua("c1", "x"), 1, 2, false, true, "{\"success\":false}");
        assertTrue(ProcessLine.lastFailed(List.of(done("c0", 0, 1), bad)));
        assertFalse(ProcessLine.lastFailed(List.of(bad, done("c2", 2, 3))));
        assertFalse(ProcessLine.lastFailed(List.of(bad, Step.thought("then", 2, 3))));
    }

    @Test
    void theCurrentStepIsTheLiveThoughtElseTheFirstRunningCall() {
        Step live = Step.liveThought("hm", 5);
        Step running = Step.call(lua("c2", "x"), 3, 0, true, false, null);
        assertSame(live, ProcessLine.current(List.of(done("c1", 1, 3), running, live)));
        assertSame(running, ProcessLine.current(List.of(done("c1", 1, 3), running)));
        assertNull(ProcessLine.current(List.of(done("c1", 1, 3))));
    }

    @Test
    void aLuaCallShowsItsProgramAndOtherToolsTheirRawArguments() {
        assertEquals("return numen.status.self()", ProcessLine.code(lua("c1", "return numen.status.self()")));
        LlmToolCall other = new LlmToolCall("c2", "todo", "{\"items\":[\"a\"]}");
        assertEquals("{\"items\":[\"a\"]}", ProcessLine.code(other));
    }

    @Test
    void aBrokenLuaCallShowsWhatWasSent() {
        LlmToolCall broken = new LlmToolCall("c1", ScriptEngine.IN_USE.toolName(), "{\"code\":\"return 1\"}");
        assertEquals("{\"code\":\"return 1\"}", ProcessLine.code(broken));
    }

    @Test
    void firstLineSkipsBlankLeadersAndKeepsTheRestOut() {
        assertEquals("first", ProcessLine.firstLine("\n  \n first \nsecond"));
        assertEquals("", ProcessLine.firstLine("  \n"));
    }
}
