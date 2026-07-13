package com.dwinovo.numen.util;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafeJsonStoreTest {
    @TempDir Path directory;

    @Test void atomicWriteKeepsPreviousValueAsValidatedBackup() throws Exception {
        Path file = directory.resolve("state.json");
        SafeJsonStore.write(file, "{\"version\":1}", SafeJsonStoreTest::version);
        SafeJsonStore.write(file, "{\"version\":2}", SafeJsonStoreTest::version);
        assertEquals(2, version(com.google.gson.JsonParser.parseString(Files.readString(file))));
        assertEquals(1, version(com.google.gson.JsonParser.parseString(Files.readString(SafeJsonStore.backup(file)))));
    }

    @Test void corruptPrimaryRecoversBackupAndQuarantinesBadFile() throws Exception {
        Path file = directory.resolve("state.json");
        SafeJsonStore.write(file, "{\"version\":1}", SafeJsonStoreTest::version);
        SafeJsonStore.write(file, "{\"version\":2}", SafeJsonStoreTest::version);
        Files.writeString(file, "not json");
        var result = SafeJsonStore.read(file, SafeJsonStoreTest::version);
        assertTrue(result.recoveredFromBackup());
        assertEquals(1, result.value().orElseThrow());
        assertTrue(Files.list(directory.resolve("diagnostics")).findAny().isPresent());
    }

    @Test void invalidTemporaryJsonNeverDamagesFormalFile() throws Exception {
        Path file = directory.resolve("state.json");
        SafeJsonStore.write(file, "{\"version\":1}", SafeJsonStoreTest::version);
        assertThrows(Exception.class, () -> SafeJsonStore.write(file, "{}", SafeJsonStoreTest::version));
        assertEquals(1, version(com.google.gson.JsonParser.parseString(Files.readString(file))));
    }

    private static Integer version(JsonElement value) {
        int version = value.getAsJsonObject().get("version").getAsInt();
        if (version < 1) throw new IllegalArgumentException("unsupported version");
        return version;
    }
}
