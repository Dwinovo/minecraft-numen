package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型档案库:一份档案一个 JSON 文件,内置在服务器目录、我的在玩家目录;档案的每个字段落盘再读回来,<b>档案里没有 key</b>,
 * 请求用的 key 按厂家从钥匙串里找(先他的、再服主的)。
 */
class ProviderLibraryTest {

    private static final UUID OWNER = UUID.randomUUID();

    @TempDir
    Path dir;

    private LibraryHub hub;
    private Keychain ownerKeys;
    private Keychain serverKeys;

    private ProviderLibrary fresh() {
        hub = LibraryHub.headless(dir);
        Io io = Io.direct(e -> {
            throw e;
        });
        ownerKeys = new Keychain(new Home(dir.resolve("players").resolve(OWNER.toString()), io));
        serverKeys = new Keychain(new Home(dir.resolve("server"), io));
        return new ProviderLibrary(hub.library(ProviderLibrary.KIND, OWNER), new Keys(ownerKeys, serverKeys),
                new ModelCatalog(dir.resolve("server").resolve("cache").resolve("models-dev.json"), () -> ""));
    }

    @Test
    void everyProfileFieldSurvivesAReloadAndTheKeyIsNotAmongThem() throws IOException {
        JsonObject compat = JsonParser.parseString("{\"thinking_format\":\"none\"}").getAsJsonObject();
        ConnectionProfile profile = new ConnectionProfile("deepseek", "chat", "deepseek-v4-flash", "sk-1",
                "https://x.example/v1", "127.0.0.1:7890", "high", 32_000, 0.4, 2048, compat);
        ProviderLibrary.Entry e = fresh().create("我的 DeepSeek", profile);

        assertEquals("my:我的 DeepSeek", e.id());
        ProviderLibrary.Entry back = fresh().get(e.id());
        assertEquals("我的 DeepSeek", back.name());
        assertEquals(profile.provider(), back.profile().provider());
        assertEquals(profile.protocol(), back.profile().protocol());
        assertEquals(profile.model(), back.profile().model());
        assertNull(back.profile().apiKey(), "key 不在档案里");
        assertEquals(profile.baseUrl(), back.profile().baseUrl());
        assertEquals(profile.proxy(), back.profile().proxy());
        assertEquals(profile.reasoningEffort(), back.profile().reasoningEffort());
        assertEquals(32_000, back.profile().ctx());
        assertEquals(0.4, back.profile().temperature());
        assertEquals(2048, back.profile().maxTokens());
        assertEquals(compat, back.profile().compat());
        Path file = dir.resolve("players").resolve(OWNER.toString()).resolve("provider").resolve("my")
                .resolve("我的 DeepSeek.json");
        assertFalse(Files.readString(file).contains("sk-1"), "磁盘上的档案文件里没有 key");
    }

    @Test
    void anOldStyleEntryFileReadsWithTheNewFieldsUnset() throws IOException {
        Path file = dir.resolve("server").resolve("provider").resolve("server").resolve("旧档案.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"provider":"deepseek","model":"deepseek-chat","reasoning_effort":"off","ctx":64000}""");
        ConnectionProfile p = fresh().get("server:旧档案").profile();
        assertEquals("deepseek", p.provider());
        assertEquals("off", p.reasoningEffort());
        assertEquals(64_000, p.ctx());
        assertNull(p.protocol());
        assertNull(p.temperature());
        assertEquals(0, p.maxTokens());
        assertNull(p.compat());
    }

    @Test
    void theRequestUsesHisOwnKeyFirstThenTheServersAndNoKeyMeansNoKey() {
        ProviderLibrary library = fresh();
        ProviderLibrary.Entry e = library.create("x", new ConnectionProfile("deepseek", null, "deepseek-chat", null,
                null, null, null, 0, null, 0, null));

        assertFalse(library.resolve(e.id()).hasApiKey());
        assertNull(library.key(e.id()));

        serverKeys.put("deepseek", "sk-server");
        assertEquals(new Keys.Found("sk-server", Keys.Source.SERVER), library.key(e.id()));
        assertTrue(library.resolve(e.id()).hasApiKey());

        ownerKeys.put("deepseek", "sk-mine");
        assertEquals(new Keys.Found("sk-mine", Keys.Source.OWNER), library.key(e.id()), "主人自己的先");
    }

    @Test
    void sameNamesGetASuffixAndARemovedProfileIsGone() {
        ProviderLibrary library = fresh();
        ConnectionProfile profile = new ConnectionProfile("deepseek", null, "m", null, null, null, null, 0, null, 0, null);

        ProviderLibrary.Entry first = library.create("档案", profile);
        ProviderLibrary.Entry second = library.create("档案", profile);

        assertEquals("my:档案", first.id());
        assertEquals("my:档案_2", second.id());
        library.remove(first.id());
        assertNull(library.get(first.id()));
        assertEquals(1, library.list().size());
    }

    @Test
    void aFileThatCannotBeReadIsSkippedAndTheOthersStay() throws IOException {
        Path bad = dir.resolve("server").resolve("provider").resolve("server").resolve("坏的.json");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "{not json");
        Files.writeString(bad.resolveSibling("好的.json"), "{\"provider\":\"openai\"}");

        ProviderLibrary library = fresh();

        assertNull(library.get("server:坏的"));
        assertEquals("openai", library.get("server:好的").profile().provider());
        assertEquals(1, library.list().size());
    }

    @Test
    void aSwitchedOffBuiltinProfileIsNotOfferedButStillThere() throws IOException {
        Path file = dir.resolve("server").resolve("provider").resolve("server").resolve("推荐.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"provider\":\"openai\"}");
        ProviderLibrary library = fresh();

        hub.library(ProviderLibrary.KIND, OWNER).setEnabled(new EntryId("server", "推荐"), false);

        assertNull(library.get("server:推荐"));
        assertTrue(library.list().isEmpty());
        assertTrue(Files.exists(file), "服务器上的内置文件不动");
    }
}
