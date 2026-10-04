package com.dwinovo.numen.program;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 程序的服务端调用在主线程上的排队:每刻有预算,按车道轮流,没轮到的留到下一刻;主线程只执行,不等任何程序。
 */
class MainQueueTest {

    /** 假的纳秒钟:任务"花了多久"就是把它拨快多少。 */
    private long now;

    private MainQueue queue() {
        return new MainQueue(() -> now);
    }

    /** 一个"花了这么久"的任务。 */
    private Runnable takes(long nanos, List<String> ran, String name) {
        return () -> {
            now += nanos;
            ran.add(name);
        };
    }

    @Test
    void aLaneRunsItsTasksInTheOrderTheyWerePosted() {
        MainQueue queue = queue();
        MainQueue.Lane lane = queue.lane();
        List<String> ran = new ArrayList<>();
        lane.post(() -> ran.add("a"));
        lane.post(() -> ran.add("b"));
        queue.drain(Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(List.of("a", "b"), ran);
    }

    @Test
    void aProgramThatUsedItsShareThisTickWaitsForTheNextOne() {
        MainQueue queue = queue();
        MainQueue.Lane lane = queue.lane();
        List<String> ran = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            lane.post(takes(2_000_000L, ran, name));
        }
        queue.drain(Long.MAX_VALUE, 3_000_000L);   // 每段程序一刻 3 毫秒:第二个跑完就用完了
        assertEquals(List.of("a", "b"), ran);
        queue.drain(Long.MAX_VALUE, 3_000_000L);
        assertEquals(List.of("a", "b", "c"), ran, "the rest ran on the next tick");
    }

    @Test
    void allTheProgramsTogetherStayInTheTicksBudgetAndStartingPointRotates() {
        MainQueue queue = queue();
        List<MainQueue.Lane> lanes = List.of(queue.lane(), queue.lane(), queue.lane());
        List<String> ran = new ArrayList<>();
        for (int i = 0; i < lanes.size(); i++) {
            for (int n = 0; n < 3; n++) {
                lanes.get(i).post(takes(2_000_000L, ran, i + "" + n));
            }
        }
        // 全部一刻 3 毫秒,每段不限:第一条车道的两个任务用完,别的车道这一刻没份
        queue.drain(3_000_000L, Long.MAX_VALUE);
        assertEquals(List.of("00", "01"), ran);
        // 下一刻从第二条车道起
        queue.drain(3_000_000L, Long.MAX_VALUE);
        assertEquals(List.of("00", "01", "10", "11"), ran, "the next tick starts one lane later");
    }

    @Test
    void aTaskThatHasStartedRunsToTheEndEvenWhenItAloneIsOverBudget() {
        MainQueue queue = queue();
        MainQueue.Lane lane = queue.lane();
        List<String> ran = new ArrayList<>();
        lane.post(takes(3_000_000L, ran, "slow"));
        lane.post(takes(1_000L, ran, "next"));
        queue.drain(1_000_000L, 1_000_000L);
        assertEquals(List.of("slow"), ran, "budget is checked between tasks");
    }

    @Test
    void aClosedLaneIsDroppedOnceItIsEmpty() {
        MainQueue queue = queue();
        MainQueue.Lane lane = queue.lane();
        List<String> ran = new ArrayList<>();
        lane.post(() -> ran.add("last"));
        lane.close();
        assertEquals(1, queue.drainAll());
        assertEquals(List.of("last"), ran, "what was posted before closing still runs");
        assertEquals(0, queue.drainAll());
    }

    @Test
    void drainAllIgnoresTheBudgetAndRunsWhatTasksPostOnTheWay() {
        MainQueue queue = queue();
        MainQueue.Lane lane = queue.lane();
        List<String> ran = new ArrayList<>();
        lane.post(() -> {
            ran.add("first");
            lane.post(() -> ran.add("posted by first"));
        });
        assertEquals(2, queue.drainAll());
        assertEquals(List.of("first", "posted by first"), ran);
    }

    /** 主线程取任务,不等程序放任务:空队列一刻也不耽误。 */
    @Test
    void drainingAnEmptyQueueReturnsAtOnce() {
        MainQueue queue = new MainQueue();
        queue.lane();
        long began = System.nanoTime();
        queue.drain(ProgramLimits.TICK_NANOS_ALL, ProgramLimits.TICK_NANOS_PER_PROGRAM);
        assertTrue(System.nanoTime() - began < 50_000_000L);
    }
}
