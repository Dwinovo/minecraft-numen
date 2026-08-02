package com.dwinovo.numen.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移台账的四种盘面:只有旧(搬)、只有新(不动)、新旧都有(不动,新为真源)、
 * 全空(无事发生)。外加幂等(跑两遍同一结果)与坏目录不炸。
 */
class ConfigMigrationsTest {

    @TempDir
    Path dir;

    @Test
    void legacyOnlyGetsRenamedWithContentIntact() throws IOException {
        Files.writeString(dir.resolve("models.json"), "{\"providers\":[]}");
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("models.json")));
        assertEquals("{\"providers\":[]}", Files.readString(dir.resolve("providers.json")));
    }

    @Test
    void currentOnlyIsUntouched() throws IOException {
        Files.writeString(dir.resolve("providers.json"), "new");
        ConfigMigrations.run(dir);
        assertEquals("new", Files.readString(dir.resolve("providers.json")));
        assertFalse(Files.exists(dir.resolve("models.json")));
    }

    @Test
    void bothPresentKeepsCurrentAsTruth() throws IOException {
        // 双份并存(升级又回滚又升级的盘面):新文件为真源,旧文件不动不删——
        // 覆盖新文件等于吃掉玩家在新版本里的改动。
        Files.writeString(dir.resolve("providers.json"), "new");
        Files.writeString(dir.resolve("models.json"), "old");
        ConfigMigrations.run(dir);
        assertEquals("new", Files.readString(dir.resolve("providers.json")));
        assertEquals("old", Files.readString(dir.resolve("models.json")));
    }

    @Test
    void emptyDirIsANoOp() {
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("providers.json")));
    }

    @Test
    void runningTwiceIsIdempotent() throws IOException {
        Files.writeString(dir.resolve("models.json"), "x");
        ConfigMigrations.run(dir);
        ConfigMigrations.run(dir);
        assertEquals("x", Files.readString(dir.resolve("providers.json")));
    }

    @Test
    void missingDirDoesNotThrow() {
        ConfigMigrations.run(dir.resolve("no-such-subdir"));
        assertTrue(true);
    }
}
