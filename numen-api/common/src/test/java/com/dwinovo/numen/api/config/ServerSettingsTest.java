package com.dwinovo.numen.api.config;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器设置里权限的那一项:硬规则默认开;文件里写了的以文件为准;没有文件就写出一份带着它的默认文件给服主看。
 */
class ServerSettingsTest {

    private static Home home(Path dir) {
        return new Home(dir, Io.direct(e -> {
            throw e;
        }));
    }

    @Test
    void hardRulesAreOnByDefault(@TempDir Path dir) {
        assertTrue(new ServerSettings(home(dir)).hardRules());
    }

    @Test
    void whatTheFileSaysWinsAndWhatItLeavesOutIsTheDefault(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"hardRules\": false}");
        ServerSettings off = new ServerSettings(home(dir));
        assertFalse(off.hardRules());

        // 文件里没写硬规则就是默认;设置里没有的键读到了不理
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"unknownAsOther\": false}");
        off.reload();
        assertTrue(off.hardRules());
    }

    @Test
    void theDefaultFileCarriesTheSetting(@TempDir Path dir) throws IOException {
        new ServerSettings(home(dir));
        JsonObject written = JsonParser.parseString(Files.readString(dir.resolve(ServerSettings.FILE))).getAsJsonObject();
        assertEquals(true, written.get("hardRules").getAsBoolean());
    }

    @Test
    void anUnreadableFileFallsBackToTheDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "this is not json");
        ServerSettings settings = new ServerSettings(home(dir));
        assertTrue(settings.hardRules());
    }
}
