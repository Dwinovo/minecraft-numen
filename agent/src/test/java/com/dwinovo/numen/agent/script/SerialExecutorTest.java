package com.dwinovo.numen.agent.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同一时刻至多一个任务在跑,先交的先跑;一个任务抛出不挡住后面的。 */
class SerialExecutorTest {

    @Test
    void tasksFromManyThreadsNeverOverlapAndKeepEachSubmittersOrder() throws InterruptedException {
        SerialExecutor serial = new SerialExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        List<String> ran = new ArrayList<>();   // 只在 serial 上改:不需要别的同步
        int threads = 8;
        int each = 200;
        CountDownLatch done = new CountDownLatch(threads * each);
        for (int t = 0; t < threads; t++) {
            int submitter = t;
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < each; i++) {
                    int n = i;
                    serial.execute(() -> {
                        if (inside.incrementAndGet() > 1) {
                            overlaps.incrementAndGet();
                        }
                        ran.add(submitter + ":" + n);
                        inside.decrementAndGet();
                        done.countDown();
                    });
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, overlaps.get());
        for (int t = 0; t < threads; t++) {
            int submitter = t;
            List<Integer> mine = ran.stream().filter(s -> s.startsWith(submitter + ":"))
                    .map(s -> Integer.parseInt(s.substring(s.indexOf(':') + 1))).toList();
            assertEquals(each, mine.size());
            assertEquals(mine.stream().sorted().toList(), mine);
        }
    }

    @Test
    void aTaskThatThrowsDoesNotStopTheOnesAfterIt() throws InterruptedException {
        SerialExecutor serial = new SerialExecutor(Executors.newVirtualThreadPerTaskExecutor());
        CountDownLatch after = new CountDownLatch(1);
        serial.execute(() -> {
            throw new IllegalStateException("on purpose");
        });
        serial.execute(after::countDown);
        assertTrue(after.await(10, TimeUnit.SECONDS));
    }
}
