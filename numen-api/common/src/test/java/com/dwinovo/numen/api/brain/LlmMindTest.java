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
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 她发不了请求的理由,在发之前就说清楚(循环据此停在 BLOCKED):没绑档案、档案对应的服务商没有 key(先找绑的人的、再找服主的)、
 * 档案没地址,以及用的是服主的 key 而绑的人今天的额度用完了——他自己的 key 不受限。档案是谁绑的,就在谁的库里解析、用谁的 key、
 * 记在谁的账上:她的另一位主人的库、key 与额度不相干。
 */
class LlmMindTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    private static final ConnectionProfile DEEPSEEK = new ConnectionProfile("deepseek", null, "deepseek-chat", null,
            null, null, null, 0, null, 0, null, 0, 0);

    @TempDir
    Path dir;

    private final Map<UUID, Keychain> keychains = new HashMap<>();
    private Io io;
    private LibraryHub hub;
    private ModelCatalog catalog;
    private Keychain ownerKeys;
    private Keychain serverKeys;
    private ServerKeyQuota quota;
    private LlmMind mind;
    private Bound profile;

    private void open(long dailyLimit) throws IOException {
        io = Io.direct(e -> {
            throw e;
        });
        Files.createDirectories(dir.resolve("server"));
        Files.writeString(dir.resolve("server/server.json"), "{\"dailyTokenLimit\":" + dailyLimit + "}");
        serverKeys = new Keychain(new Home(dir.resolve("server"), io));
        quota = new ServerKeyQuota(new ServerSettings(new Home(dir.resolve("server"), io), false),
                new Home(dir.resolve("server"), io), () -> LocalDate.of(2026, 10, 18));
        hub = LibraryHub.headless(dir);
        catalog = new ModelCatalog(dir.resolve("cache.json"), () -> "");
        ownerKeys = keys(OWNER);
        profile = new Bound(OWNER, EntryId.parse(providers(OWNER).create("档案", DEEPSEEK).id()));
        mind = new LlmMind(this::providers, catalog, quota);
    }

    /** 这位玩家自己的钥匙串。 */
    private Keychain keys(UUID player) {
        return keychains.computeIfAbsent(player, id -> new Keychain(new Home(dir.resolve("players/" + id), io)));
    }

    /** 这位玩家看到的档案库:服务器的加他的"我的",key 先找他的再找服主的。 */
    private ProviderLibrary providers(UUID player) {
        return new ProviderLibrary(hub.library(ProviderLibrary.KIND, player), new Keys(keys(player), serverKeys), catalog);
    }

    @Test
    void noProfileOrNoKeyOrNoAddressEachSaysSo() throws IOException {
        open(0);

        assertEquals(Cause.UNBOUND, mind.unavailable(null).code());
        assertEquals(Cause.UNBOUND, mind.unavailable(new Bound(OWNER, EntryId.mine("不存在"))).code());
        Cause noKey = mind.unavailable(profile);
        assertEquals(Cause.NO_KEY, noKey.code());
        assertEquals("档案", noKey.arg(0));

        ownerKeys.put("deepseek", "sk-mine");
        assertNull(mind.unavailable(profile), "有 key,deepseek 的缺省地址在目录里");

        Bound noUrl = new Bound(OWNER, EntryId.parse(providers(OWNER).create("自定义", new ConnectionProfile("custom",
                "chat", "m", null, null, null, null, 0, null, 0, null, 0, 0)).id()));
        ownerKeys.put("custom", "sk");
        assertEquals(Cause.NO_URL, mind.unavailable(noUrl).code());
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

    /** 两位主人各有一条同名的档案:绑的是谁的,就解析出谁的那一条、用谁的 key;另一位的库里没有的条目,凭他解析不到。 */
    @Test
    void aProfileResolvesInTheLibraryOfWhoBoundItWithHisKey() throws IOException {
        open(0);
        Bound friends = new Bound(FRIEND, EntryId.parse(providers(FRIEND).create("档案", new ConnectionProfile(
                "deepseek", null, "deepseek-reasoner", null, null, null, null, 0, null, 0, null, 0, 0)).id()));
        assertEquals(profile.id(), friends.id(), "两个人的条目同名");
        keys(FRIEND).put("deepseek", "sk-friend");

        assertNull(mind.unavailable(friends), "朋友有自己的 key");
        assertEquals("sk-friend", mind.endpoint(friends).apiKey());
        assertEquals("deepseek-reasoner", mind.endpoint(friends).model().id());
        assertEquals(Cause.NO_KEY, mind.unavailable(profile).code(), "创建者绑的那一条不沾朋友的 key");
        assertEquals("deepseek-chat", mind.endpoint(profile).model().id());

        Bound onlyHis = new Bound(FRIEND, EntryId.parse(providers(FRIEND).create("只有他有", DEEPSEEK).id()));
        assertNull(mind.unavailable(onlyHis));
        assertEquals(Cause.UNBOUND, mind.unavailable(new Bound(OWNER, onlyHis.id())).code(), "创建者的库里没有这一条");
        assertFalse(mind.endpoint(new Bound(OWNER, onlyHis.id())).hasApiKey());
    }

    /** 用服主的 key 时,限额看的是绑档案的那个人今天用了多少:他用完了,别人绑的档案照常。 */
    @Test
    void theServersKeyIsMeteredOnWhoBoundTheProfile() throws IOException {
        open(1000);
        serverKeys.put("deepseek", "sk-server");
        Bound friends = new Bound(FRIEND, EntryId.parse(providers(FRIEND).create("朋友的档案", DEEPSEEK).id()));

        quota.charge(FRIEND, 1000);

        assertEquals(Cause.QUOTA, mind.unavailable(friends).code());
        assertNull(mind.unavailable(profile), "创建者绑的档案记在创建者名下,他今天还没用");
    }
}
