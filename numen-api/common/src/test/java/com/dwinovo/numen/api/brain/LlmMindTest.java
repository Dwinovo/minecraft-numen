package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.config.ServerSettings;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.keys.ServerKeyQuota;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 她发不了请求的理由,在发之前就说清楚(循环据此停在 BLOCKED):没绑档案、档案对应的服务商没有 key(先找主人的、再找服主的)、
 * 档案没地址,以及用的是服主的 key 而这位玩家今天的额度用完了——主人自己的 key 不受限。
 */
class LlmMindTest {

    private static final UUID OWNER = UUID.randomUUID();

    @TempDir
    Path dir;

    private Keychain ownerKeys;
    private Keychain serverKeys;
    private ServerKeyQuota quota;
    private LlmMind mind;
    private String profile;

    private void open(long dailyLimit) throws IOException {
        Io io = Io.direct(e -> {
            throw e;
        });
        Files.createDirectories(dir.resolve("server"));
        Files.writeString(dir.resolve("server/server.json"), "{\"dailyTokenLimit\":" + dailyLimit + "}");
        ownerKeys = new Keychain(new Home(dir.resolve("players/a"), io));
        serverKeys = new Keychain(new Home(dir.resolve("server"), io));
        quota = new ServerKeyQuota(new ServerSettings(new Home(dir.resolve("server"), io), false),
                new Home(dir.resolve("server"), io), () -> LocalDate.of(2026, 10, 18));
        ProviderLibrary providers = new ProviderLibrary(LibraryHub.headless(dir).library(ProviderLibrary.KIND, OWNER),
                new Keys(ownerKeys, serverKeys), new ModelCatalog(dir.resolve("cache.json"), () -> ""));
        profile = providers.create("档案", new ConnectionProfile("deepseek", null, "deepseek-chat", null, null, null,
                null, 0, null, 0, null)).id();
        mind = new LlmMind(providers, OWNER, quota);
    }

    @Test
    void noProfileOrNoKeyOrNoAddressEachSaysSo() throws IOException {
        open(0);

        assertEquals(Cause.UNBOUND, mind.unavailable(null).code());
        assertEquals(Cause.UNBOUND, mind.unavailable("my:不存在").code());
        Cause noKey = mind.unavailable(profile);
        assertEquals(Cause.NO_KEY, noKey.code());
        assertEquals("档案", noKey.arg(0));

        ownerKeys.put("deepseek", "sk-mine");
        assertNull(mind.unavailable(profile), "有 key,deepseek 的缺省地址在目录里");

        ProviderLibrary custom = new ProviderLibrary(LibraryHub.headless(dir).library(ProviderLibrary.KIND, OWNER),
                new Keys(ownerKeys, serverKeys), new ModelCatalog(dir.resolve("cache.json"), () -> ""));
        String noUrl = custom.create("自定义", new ConnectionProfile("custom", "chat", "m", null, null, null, null, 0,
                null, 0, null)).id();
        ownerKeys.put("custom", "sk");
        assertEquals(Cause.NO_URL, new LlmMind(custom, OWNER, quota).unavailable(noUrl).code());
    }

    @Test
    void theServersKeyStopsAtTheDailyLimitButHisOwnKeyNever() throws IOException {
        open(1000);
        serverKeys.put("deepseek", "sk-server");
        assertNull(mind.unavailable(profile));

        quota.charge(OWNER, 1000);

        assertEquals(Cause.QUOTA, mind.unavailable(profile).code());
        ownerKeys.put("deepseek", "sk-mine");
        assertNull(mind.unavailable(profile), "他自己的 key 不受服主的限额");
    }

    @Test
    void withoutALimitTheServersKeyIsAlwaysAvailable() throws IOException {
        open(0);
        serverKeys.put("deepseek", "sk-server");
        quota.charge(OWNER, 10_000_000);

        assertNull(mind.unavailable(profile));
    }
}
