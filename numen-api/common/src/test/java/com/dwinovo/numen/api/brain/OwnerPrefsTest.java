package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主人的偏好:没有文件是默认档,改了落盘、重开读回来,越界的夹回范围里。 */
class OwnerPrefsTest {

    private static OwnerPrefs open(Path file) {
        return new OwnerPrefs(new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> { })));
    }

    @Test
    void startsAtTheDefaultLevelAndRemembersWhatTheOwnerSet(@TempDir Path dir) {
        Path file = dir.resolve("prefs.json");
        assertEquals(EventQueue.DEFAULT_LEVEL, open(file).initiative());

        open(file).setInitiative(7);

        assertEquals(7, open(file).initiative());
    }

    @Test
    void aLevelOutsideTheScaleIsClamped(@TempDir Path dir) {
        OwnerPrefs prefs = open(dir.resolve("prefs.json"));

        prefs.setInitiative(99);
        assertEquals(EventQueue.clampLevel(99), prefs.initiative());
        prefs.setInitiative(-4);
        assertEquals(EventQueue.clampLevel(-4), prefs.initiative());
    }

    @Test
    void theSwitchesOnLibraryEntriesAreHisOwnAndAreRememberedAcrossARestart(@TempDir Path dir) {
        Path file = dir.resolve("prefs.json");
        OwnerPrefs prefs = open(file);
        EntryId xiaoyan = new EntryId("numen", "小焰");
        assertTrue(prefs.enabled("persona", xiaoyan), "缺省全开");

        prefs.set("persona", xiaoyan, false);
        prefs.set("skills", new EntryId("numen", "mining"), false);

        OwnerPrefs again = open(file);
        assertFalse(again.enabled("persona", xiaoyan));
        assertFalse(again.enabled("skills", new EntryId("numen", "mining")));
        assertTrue(again.enabled("skills", xiaoyan), "按库分开记");
        assertEquals(EventQueue.DEFAULT_LEVEL, again.initiative(), "开关不动别的偏好");

        again.set("persona", xiaoyan, true);
        assertTrue(open(file).enabled("persona", xiaoyan));
    }

    @Test
    void theWarningAboutTheServerOwnerSeeingHisKeyIsShownOnceAndRemembered(@TempDir Path dir) {
        Path file = dir.resolve("prefs.json");
        OwnerPrefs prefs = open(file);
        assertFalse(prefs.keyNoticeShown());

        prefs.setKeyNoticeShown();
        prefs.setInitiative(3);

        assertTrue(open(file).keyNoticeShown());
        assertEquals(3, open(file).initiative(), "写一项不丢另一项");
    }
}

