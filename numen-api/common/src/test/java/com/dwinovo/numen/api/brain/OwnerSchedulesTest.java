package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定时的账:到期只交一次、错过不补、重载不重发、主人不在时留着合成一次;条数与间隔的限制。 */
class OwnerSchedulesTest {

    private static final long HOUR = OwnerSchedules.MIN_EVERY_TICKS;

    @TempDir
    Path dir;
    private JsonDoc doc;
    private OwnerSchedules book;

    @BeforeEach
    void open() {
        doc = new JsonDoc(dir.resolve("schedules.json"), JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        }));
        book = reopen();
    }

    private OwnerSchedules reopen() {
        return new OwnerSchedules(doc, doc.read().orElse(null));
    }

    @Test
    void aScheduleIsDueOnceAtItsTimeAndThenWaitsAFullInterval() {
        book.put(null, "看看炉子", 2 * HOUR, 100);

        assertEquals(List.of(), book.due(100 + 2 * HOUR - 1));
        assertEquals(List.of("看看炉子"), book.due(100 + 2 * HOUR));
        assertEquals(List.of(), book.due(100 + 2 * HOUR), "the same tick does not hand it out twice");
        assertEquals(List.of("看看炉子"), book.due(100 + 4 * HOUR), "the next interval counts from the firing");
    }

    @Test
    void missedIntervalsAreNotMadeUpAndTheNextOneCountsFromTheLateFiring() {
        book.put(null, "巡逻", HOUR, 0);

        assertEquals(List.of("巡逻"), book.due(5 * HOUR + 500), "five intervals missed, one delivery");
        assertEquals(List.of(), book.due(6 * HOUR));
        assertEquals(List.of("巡逻"), book.due(6 * HOUR + 500));
    }

    @Test
    void aFiringIsOnDiskBeforeItIsDeliveredSoAReloadDoesNotResendIt() {
        book.put(null, "喂鸡", HOUR, 0);
        assertEquals(List.of("喂鸡"), book.due(HOUR));

        OwnerSchedules reloaded = reopen();

        assertEquals(List.of(), reloaded.due(HOUR));
        assertEquals(List.of("喂鸡"), reloaded.due(2 * HOUR));
        assertEquals(book.list().get(0).id(), reloaded.list().get(0).id(), "the id follows the save");
    }

    @Test
    void whatCameDueWhileTheOwnerWasAwayIsHandedOverOnceWhenHeAsksAgain() {
        book.put(null, "早安", HOUR, 0);
        book.put(null, "晚安", 3 * HOUR, 0);

        // 主人不在时没人问 due;回来后的第一次问到,各条各一次,不管错过了几个间隔
        assertEquals(List.of("早安", "晚安"), book.due(50 * HOUR));
        assertEquals(List.of(), book.due(50 * HOUR + 1));
    }

    @Test
    void editingRestartsTheClockAndDeletingRemovesIt() {
        String id = book.put(null, "旧话", HOUR, 0).id();

        book.put(id, "新话", 2 * HOUR, 500);

        assertEquals(List.of(), book.due(HOUR + 500));
        assertEquals(List.of("新话"), book.due(500 + 2 * HOUR));
        assertTrue(book.delete(id));
        assertEquals(List.of(), reopen().list());
        assertFalse(book.delete(id));
    }

    @Test
    void limitsOnCountIntervalAndWords() {
        for (int i = 0; i < OwnerSchedules.MAX; i++) {
            book.put(null, "第" + i + "条", HOUR, 0);
        }

        assertThrows(IllegalArgumentException.class, () -> book.put(null, "太多了", HOUR, 0));
        assertThrows(IllegalArgumentException.class, () -> book.put("没有这条", "x", HOUR, 0));
        OwnerSchedules fresh = new OwnerSchedules(doc, null);
        assertThrows(IllegalArgumentException.class, () -> fresh.put(null, "太快", HOUR - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> fresh.put(null, "太慢", OwnerSchedules.MAX_EVERY_TICKS + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> fresh.put(null, "  ", HOUR, 0));
        assertThrows(IllegalArgumentException.class,
                () -> fresh.put(null, "长".repeat(OwnerSchedules.MAX_TEXT + 1), HOUR, 0));
    }
}
