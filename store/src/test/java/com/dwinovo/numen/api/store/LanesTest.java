package com.dwinovo.numen.api.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanesTest {

    private final List<StoreException> errors = Collections.synchronizedList(new ArrayList<>());

    @Test
    void oneKeysWritesRunInSubmissionOrderAndDifferentKeysDoNotWaitForEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Lanes lanes = new Lanes(pool, errors::add);
            CountDownLatch release = new CountDownLatch(1);
            List<String> order = Collections.synchronizedList(new ArrayList<>());

            lanes.io("a").executor().execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("a1");
            });
            lanes.io("a").executor().execute(() -> order.add("a2"));
            CountDownLatch bDone = new CountDownLatch(1);
            lanes.io("b").executor().execute(() -> {
                order.add("b1");
                bDone.countDown();
            });

            assertTrue(bDone.await(5, TimeUnit.SECONDS), "a 的车道堵着,b 的照样走");
            assertEquals(List.of("b1"), order);
            release.countDown();
            assertTrue(lanes.drain(5, TimeUnit.SECONDS));
            assertEquals(List.of("b1", "a1", "a2"), order, "同一个键按提交顺序");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void drainWaitsForTheWritesAlreadySubmittedAndGivesUpAtTheDeadline(@TempDir Path dir) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Lanes lanes = new Lanes(pool, errors::add);
            JsonDoc doc = new JsonDoc(dir.resolve("x.json"), JsonDoc.Format.of(1), lanes.io("a"));
            JsonObject body = new JsonObject();
            body.addProperty("k", "v");
            doc.write(body);
            assertTrue(lanes.drain(5, TimeUnit.SECONDS));
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("x.json")), "排空之后写已经落下去");

            CountDownLatch never = new CountDownLatch(1);
            lanes.io("a").executor().execute(() -> {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertFalse(lanes.drain(50, TimeUnit.MILLISECONDS), "还有写没下去,如实说没排空");
            never.countDown();
            assertTrue(lanes.drain(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
