package com.dwinovo.numen.api.permission;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服主的两个开关:硬规则默认开;说不清是谁的方块,专用服务器上默认按别人的算、单机与局域网默认问主人;文件里写了的以文件为准;
 * 专用服上没有文件就写出一份默认的给服主看。
 */
class PermissionConfigTest {

    @Test
    void defaultsDependOnWhetherTheServerIsDedicated() {
        assertEquals(new PermissionConfig(true, true), PermissionConfig.parse(new JsonObject(), true));
        assertEquals(new PermissionConfig(true, false), PermissionConfig.parse(new JsonObject(), false));
    }

    @Test
    void whatTheFileSaysWinsAndWhatItLeavesOutIsTheDefault() {
        JsonObject off = new JsonObject();
        off.addProperty("hard_rules", false);
        assertEquals(new PermissionConfig(false, true), PermissionConfig.parse(off, true));
        JsonObject lenient = new JsonObject();
        lenient.addProperty("unknown_as_other", false);
        assertEquals(new PermissionConfig(true, false), PermissionConfig.parse(lenient, true),
                "服主可以让专用服也问主人");
    }

    @Test
    void aDedicatedServerWithoutAFileGetsOneWrittenAndReadsItBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config").resolve(PermissionConfig.FILE);
        assertEquals(new PermissionConfig(true, true), PermissionConfig.load(file, true));
        assertTrue(Files.exists(file), "服主打开就看得见有什么可改");
        Files.writeString(file, "{\"hard_rules\": false, \"unknown_as_other\": false}");
        assertEquals(new PermissionConfig(false, false), PermissionConfig.load(file, true));
    }

    @Test
    void aSingleplayerWorldWritesNothing(@TempDir Path dir) {
        Path file = dir.resolve(PermissionConfig.FILE);
        assertEquals(new PermissionConfig(true, false), PermissionConfig.load(file, false));
        assertFalse(Files.exists(file));
    }

    @Test
    void anUnreadableFileFallsBackToTheDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(PermissionConfig.FILE);
        Files.writeString(file, "this is not json");
        assertEquals(new PermissionConfig(true, true), PermissionConfig.load(file, true));
    }
}
