package com.dwinovo.numen.api.keys;

import com.dwinovo.numen.api.config.ServerSettings;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钥匙串:一个服务一把 key,存在归属根下的 {@code keys/<服务>.key};用的时候先找主人自己的、再找服主的,服主的受每人每天的 token 限额,
 * 主人自己的不受限。
 */
class KeysTest {

    private static final UUID HER_OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir
    Path dir;

    private Home home(String name) {
        return new Home(dir.resolve(name), Io.direct(e -> {
            throw e;
        }));
    }

    @Test
    void aKeyIsPerServiceAndSurvivesARestartAndAnEmptyKeyRemovesIt() throws IOException {
        Keychain keys = new Keychain(home("players/a"));

        keys.put("deepseek", "  sk-1\n");
        keys.put("openai", "sk-2");

        assertEquals("sk-1", keys.get("deepseek"), "首尾空白不是 key 的一部分");
        assertEquals("sk-1", Files.readString(dir.resolve("players/a/keys/deepseek.key")));
        assertEquals("sk-2", new Keychain(home("players/a")).get("openai"), "重启读得回来");
        assertEquals(java.util.Set.of("deepseek", "openai"), keys.services());

        keys.put("deepseek", "");
        assertNull(keys.get("deepseek"));
        assertFalse(Files.exists(dir.resolve("players/a/keys/deepseek.key")));
        assertNull(new Keychain(home("players/a")).get("deepseek"));
    }

    @Test
    void aServiceNameThatCouldEscapeItsDirectoryIsRefused() {
        Keychain keys = new Keychain(home("server"));

        assertThrows(IllegalArgumentException.class, () -> keys.put("../x", "k"));
        assertThrows(IllegalArgumentException.class, () -> keys.put("a/b", "k"));
        assertNull(keys.get("../x"));
    }

    @Test
    void hisOwnKeyIsUsedBeforeTheServersAndTheSourceIsSaid() {
        Keychain mine = new Keychain(home("players/a"));
        Keychain server = new Keychain(home("server"));
        Keys keys = new Keys(mine, server);

        assertNull(keys.find("deepseek"));

        server.put("deepseek", "sk-server");
        assertEquals(new Keys.Found("sk-server", Keys.Source.SERVER), keys.find("deepseek"));

        mine.put("deepseek", "sk-mine");
        assertEquals(new Keys.Found("sk-mine", Keys.Source.OWNER), keys.find("deepseek"));
        assertFalse(keys.find("deepseek").toString().contains("sk-mine"), "key 不会被打进日志");
    }

    @Test
    void theTwoKeychainsDoNotSeeEachOther() {
        Keychain mine = new Keychain(home("players/a"));
        Keychain other = new Keychain(home("players/b"));
        mine.put("deepseek", "sk-a");

        assertNull(other.get("deepseek"));
        assertNull(new Keychain(home("server")).get("deepseek"));
    }

    // ---- 服主的 key 的每人每天限额 ----

    private ServerKeyQuota quota(long limit, AtomicReference<LocalDate> today) throws IOException {
        Files.createDirectories(dir.resolve("server"));
        Files.writeString(dir.resolve("server/server.json"), "{\"v\":1,\"dailyTokenLimit\":" + limit + "}");
        return new ServerKeyQuota(new ServerSettings(home("server"), false), home("server"), today::get);
    }

    @Test
    void withoutALimitNobodyIsEverCutOff() throws IOException {
        ServerKeyQuota quota = quota(0, new AtomicReference<>(LocalDate.of(2026, 10, 18)));

        quota.charge(HER_OWNER, 10_000_000);

        assertFalse(quota.exceeded(HER_OWNER));
        assertEquals(10_000_000, quota.used(HER_OWNER), "不限也记用量,服主查得到");
    }

    @Test
    void aPlayerIsCutOffAfterTheLimitAndOthersAreNotAndTomorrowStartsOver() throws IOException {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 18));
        ServerKeyQuota quota = quota(1000, today);

        quota.charge(HER_OWNER, 600);
        assertFalse(quota.exceeded(HER_OWNER));
        quota.charge(HER_OWNER, 500);

        assertTrue(quota.exceeded(HER_OWNER));
        assertFalse(quota.exceeded(OTHER), "额度是每人的");
        assertEquals(1100, quota.usedToday().get(HER_OWNER));

        today.set(LocalDate.of(2026, 10, 19));
        assertFalse(quota.exceeded(HER_OWNER), "第二天重新记");
        assertEquals(0, quota.used(HER_OWNER));
    }

    @Test
    void theUsageSurvivesARestartButNotTheNextDay() throws IOException {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 18));
        quota(1000, today).charge(HER_OWNER, 700);

        ServerKeyQuota restarted = new ServerKeyQuota(new ServerSettings(home("server"), false), home("server"), today::get);
        assertEquals(700, restarted.used(HER_OWNER));

        today.set(LocalDate.of(2026, 10, 19));
        ServerKeyQuota nextDay = new ServerKeyQuota(new ServerSettings(home("server"), false), home("server"), today::get);
        assertEquals(0, nextDay.used(HER_OWNER));
    }

    @Test
    void theServerSettingsFileIsWrittenWithTheDefaultsForTheAdminToFindAndEditAndReread() throws IOException {
        ServerSettings settings = new ServerSettings(home("server"), false);

        assertEquals(0, settings.dailyTokenLimit(), "缺省不限");
        assertTrue(Files.readString(dir.resolve("server/server.json")).contains("dailyTokenLimit"));

        Files.writeString(dir.resolve("server/server.json"), "{\"dailyTokenLimit\": 5000}");
        settings.reload();
        assertEquals(5000, settings.dailyTokenLimit());

        Files.writeString(dir.resolve("server/server.json"), "{garbage");
        settings.reload();
        assertEquals(0, settings.dailyTokenLimit(), "读不通用缺省,不拦着服务器起来");
    }
}
