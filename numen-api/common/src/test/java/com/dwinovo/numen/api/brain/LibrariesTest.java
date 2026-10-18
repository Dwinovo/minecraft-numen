package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.MojangSkinLookup;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 谁能读写哪种库、能看到什么:判据只在这一处,脱敏发生在服务端编码的地方。内容库(人设、模型档案、技能、MCP 工具)玩家看到内置与我的
 * 两组,同一套操作;内置的只有管理员能改,玩家只读、能开关、能复制到我的。
 */
class LibrariesTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final Map<String, String> config = new HashMap<>(Map.of("proxy", "", "prompt", ""));
    private final Map<UUID, OwnerPrefs> prefs = new HashMap<>();
    private final Map<UUID, Keychain> ownerKeys = new HashMap<>();
    private LibraryHub hub;
    private Keychain serverKeys;
    private ModelCatalog catalog;
    private boolean multiplayer;
    /** 假的签名队:不联网,签出来的都是 value=v-手臂模型;签好一条(写进货架之后)闸门放行一次。 */
    private SkinSigning signer;
    private java.util.concurrent.Semaphore signed;
    private final Map<UUID, PermissionStore> permissionStores = new HashMap<>();
    private final Map<UUID, Map<UUID, String>> companionsOf = new HashMap<>();

    private Libraries libraries(Path dir) {
        return libraries(dir, LibraryHub.headless(dir));
    }

    private Libraries libraries(Path dir, LibraryHub opened) {
        hub = opened;
        Io io = Io.direct(e -> {
            throw e;
        });
        catalog = new ModelCatalog(dir.resolve("server").resolve("cache").resolve("models-dev.json"), () -> "");
        serverKeys = new Keychain(new Home(dir.resolve("server"), io));
        // 服主放在服务器目录里的一份推荐档案,key 在服主的钥匙串里
        hub.builtin(ProviderLibrary.KIND).write(new EntryId("server", "服主的 DeepSeek"), ProviderLibrary.write(
                new ConnectionProfile("deepseek", null, "deepseek-chat", null, "https://relay.example",
                        "127.0.0.1:7890", null, 0, null, 0, null)));
        serverKeys.put("deepseek", "sk-secret");
        signed = new java.util.concurrent.Semaphore(0);
        signer = new SkinSigning(new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                return new MineSkinClient.Signed("v-" + variant, "s");
            }

            @Override
            public byte[] fetch(String url) {
                return PNG_64;
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return new MojangSkinLookup.Result(new MojangSkins.Skin("mojang-value", "mojang-sig"), null);
            }

            @Override
            public byte[] texture(String url) {
                return PNG_64;
            }
        }, () -> null, Runnable::run, owner -> { }, (request, outcome) -> signed.release(), ms -> { });
        return new Libraries(new Libraries.Sources() {
            @Override
            public Library library(LibraryKind kind, UUID owner) {
                return hub.library(kind, owner);
            }

            @Override
            public ProviderLibrary providers(UUID owner) {
                return new ProviderLibrary(hub.library(ProviderLibrary.KIND, owner),
                        new Keys(ownerKeys(owner), serverKeys), catalog);
            }

            @Override
            public OwnerPrefs prefs(UUID owner) {
                return prefs.computeIfAbsent(owner, id -> new OwnerPrefs(new JsonDoc(
                        dir.resolve("players").resolve(id.toString()).resolve("prefs.json"), JsonDoc.Format.of(1), io)));
            }

            @Override
            public List<UUID> companions(UUID owner) {
                return List.of();
            }

            @Override
            public Shipped.Manifest manifest() {
                return hub.manifest();
            }

            @Override
            public Keychain ownerKeys(UUID owner) {
                return ownerKeys.computeIfAbsent(owner,
                        id -> new Keychain(new Home(dir.resolve("players").resolve(id.toString()), io)));
            }

            @Override
            public Keychain serverKeys() {
                return serverKeys;
            }

            @Override
            public PermissionLibrary.Ground permission(UUID owner) {
                PermissionStore store = permissionStores.computeIfAbsent(owner, id -> new PermissionStore());
                return new PermissionLibrary.Ground() {
                    @Override
                    public PermissionStore store() {
                        return store;
                    }

                    @Override
                    public boolean hardRules() {
                        return true;
                    }

                    @Override
                    public Map<UUID, String> companions() {
                        return companionsOf.getOrDefault(owner, Map.of());
                    }
                };
            }

            @Override
            public McpClientManager sharedMcp() {
                return null;
            }

            @Override
            public McpClientManager ownerMcp(UUID owner) {
                return null;
            }

            @Override
            public void reconcileMcp(UUID owner) {
            }

            @Override
            public boolean multiplayer() {
                return multiplayer;
            }

            @Override
            public com.dwinovo.numen.api.skin.SkinSigning signer() {
                return signer;
            }

            @Override
            public Libraries.ServerConfig config() {
                return new Libraries.ServerConfig() {
                    @Override
                    public String proxy() {
                        return config.get("proxy");
                    }

                    @Override
                    public String systemPrompt() {
                        return config.get("prompt");
                    }

                    @Override
                    public void set(String proxy, String systemPrompt) {
                        config.put("proxy", proxy);
                        config.put("prompt", systemPrompt);
                    }
                };
            }
        }, catalog);
    }

    /** 一张 64×64 png 的头部(检查只看文件头与宽高)。 */
    private static final byte[] PNG_64 = pngHeader(64, 64);

    static byte[] pngHeader(int width, int height) {
        byte[] png = new byte[33];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(magic, 0, png, 0, 8);
        png[11] = 13;
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        for (int i = 0; i < 4; i++) {
            png[16 + i] = (byte) (width >> (24 - 8 * i));
            png[20 + i] = (byte) (height >> (24 - 8 * i));
        }
        return png;
    }

    private static JsonObject body(String... keyValues) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            o.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    private static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    /** 一张表里 id 对得上、在这一组的那一行。 */
    private static JsonObject row(String table, String id, String group) {
        for (JsonElement e : array(table)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.get("id").getAsString().equals(id) && o.get("group").getAsString().equals(group)) {
                return o;
            }
        }
        return null;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    // ---- 模型档案 ----

    @Test
    void anOrdinaryPlayerSeesWhichBuiltinProfilesThereAreButNeverAnAddressOrAProxy(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        String seen = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.LIST, new JsonObject()).entries();

        JsonObject profile = row(seen, "server:服主的 DeepSeek", "builtin");
        assertEquals("服主的 DeepSeek", profile.get("name").getAsString());
        assertEquals("deepseek-chat", profile.get("model").getAsString());
        assertEquals("server", profile.get("keySource").getAsString(), "他会用服主的 key,但看不到它");
        assertFalse(seen.contains("sk-secret") || seen.contains("relay.example") || seen.contains("7890"), seen);
    }

    @Test
    void anAdminSeesTheWholeBuiltinProfileButTheKeyIsNeverInAProfile(@TempDir Path dir) {
        String seen = libraries(dir).handle(ADMIN, true, Libraries.PROVIDER, Libraries.LIST, new JsonObject()).entries();

        assertTrue(seen.contains("relay.example") && seen.contains("7890"));
        assertFalse(seen.contains("sk-secret"), "key 在钥匙串里,不在档案的行里");
    }

    @Test
    void aNewEntrysAnswerSaysItsIdAndChangingAnExistingOneDoesNot(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result made = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "新的", "provider", "openai"));
        assertEquals("my:新的", createdIn(made.entries()), "新建的答复明说新条目的 id");

        Libraries.Result changed = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "新的", "provider", "openai", "model", "gpt", "id", "my:新的"));
        assertNull(changed.problem());
        assertNull(createdIn(changed.entries()), "改已有的不带");

        Libraries.Result refused = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "", "provider", "openai"));
        assertNotNull(refused.problem());
        assertNull(createdIn(refused.entries()), "没办成的不带");
    }

    private static String createdIn(String entries) {
        for (JsonElement e : array(entries)) {
            if (e.getAsJsonObject().has(Libraries.CREATED)) {
                return e.getAsJsonObject().get(Libraries.CREATED).getAsString();
            }
        }
        return null;
    }

    @Test
    void anOrdinaryPlayerCannotChangeABuiltinProfileButOwnsHisOwn(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result put = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "偷偷改", "group", "builtin", "provider", "openai", "id", "server:服主的 DeepSeek"));
        Libraries.Result delete = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.DELETE,
                body("id", "server:服主的 DeepSeek", "group", "builtin"));
        assertNotNull(put.problem());
        assertNotNull(delete.problem());
        assertNotNull(row(libraries.view(ADMIN, true, Libraries.PROVIDER), "server:服主的 DeepSeek", "builtin"), "什么也没变");

        Libraries.Result own = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "我的", "provider", "openai", "model", "gpt", "base_url", "http://mine.example"));
        assertNull(own.problem());
        JsonObject mine = row(own.entries(), "my:我的", "mine");
        assertEquals("http://mine.example", mine.get("base_url").getAsString(), "自己的档案是完整的");
        assertNull(row(libraries.view(OTHER, false, Libraries.PROVIDER), "my:我的", "mine"), "别人看不到");
        assertFalse(own.shared(), "只改了自己的,不用告诉别的玩家");
    }

    @Test
    void anAdminAddsEditsAndDeletesABuiltinProfile(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result added = libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.PUT,
                body("name", "第二个", "provider", "openai", "model", "gpt", "group", "builtin"));
        assertNull(added.problem());
        assertTrue(added.shared(), "内置的变了,在线的别人也要收到新表");
        JsonObject second = row(added.entries(), "server:第二个", "builtin");
        assertNotNull(second);

        second.addProperty("model", "gpt-5");
        assertNull(libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.PUT, second).problem());
        assertTrue(libraries.view(ADMIN, true, Libraries.PROVIDER).contains("gpt-5"));

        assertNull(libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.DELETE,
                body("id", "server:第二个", "group", "builtin")).problem());
        assertNull(row(libraries.view(ADMIN, true, Libraries.PROVIDER), "server:第二个", "builtin"));
    }

    @Test
    void renamingAnOwnProfileMovesItToTheNewNameAndLeavesNoOldOne(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT, body("name", "甲", "provider", "openai"));

        Libraries.Result renamed = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("id", "my:甲", "name", "乙", "provider", "openai"));

        assertNull(renamed.problem());
        assertNull(row(renamed.entries(), "my:甲", "mine"));
        assertNotNull(row(renamed.entries(), "my:乙", "mine"));
    }

    // ---- 钥匙串 ----

    @Test
    void aPlayersKeysAreHisOwnAndTheServersAreTheAdminsAndOthersOnlyKnowThereIsOne(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertNull(libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "sk-mine")).problem());

        JsonObject own = array(libraries.view(PLAYER, false, Libraries.KEY)).get(0).getAsJsonObject();
        assertEquals("sk-mine", own.get("mine").getAsString(), "他看得到自己的");
        assertTrue(own.get("serverSet").getAsBoolean());
        assertFalse(own.has("server"), "服主的 key 他看不到");
        assertFalse(libraries.view(OTHER, false, Libraries.KEY).contains("sk-mine"), "别人看不到");
        assertFalse(libraries.view(PLAYER, false, Libraries.KEY).contains("sk-secret"));

        assertNotNull(libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "sk-evil", "scope", "server")).problem());
        assertEquals("sk-secret", serverKeys.get("deepseek"), "服主的没被动");
        assertEquals("sk-secret", array(libraries.view(ADMIN, true, Libraries.KEY)).get(0).getAsJsonObject()
                .get("server").getAsString(), "管理员看得到、改得了");
        assertNull(libraries.handle(ADMIN, true, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "sk-new", "scope", "server")).problem());
        assertEquals("sk-new", serverKeys.get("deepseek"));
    }

    @Test
    void anEmptyKeyRemovesItAndANameThatIsNotAServiceIsRefused(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT, body("service", "deepseek", "key", "sk-mine"));

        assertNull(libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "")).problem());
        assertFalse(libraries.view(PLAYER, false, Libraries.KEY).contains("sk-mine"));
        assertNotNull(libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "../etc", "key", "x")).problem());
    }

    @Test
    void onAMultiplayerServerTheFirstKeyASavesIsFollowedByAWarningOnce(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        multiplayer = true;

        String first = libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "sk-1")).entries();
        String second = libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "openai", "key", "sk-2")).entries();
        String another = libraries.handle(OTHER, false, Libraries.KEY, Libraries.PUT,
                body("service", "openai", "key", "sk-3")).entries();

        assertTrue(first.contains("\"notice\":\"" + Libraries.NOTICE_OWNER_SEES_KEY + "\""), first);
        assertFalse(second.contains("notice"), "提醒一次就够了");
        assertTrue(another.contains("notice"), "每个玩家各一次");
    }

    @Test
    void inSingleplayerTheHostIsNotWarnedThatHeCanSeeHisOwnKey(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertFalse(libraries.handle(PLAYER, false, Libraries.KEY, Libraries.PUT,
                body("service", "deepseek", "key", "sk-1")).entries().contains("notice"));
    }

    // ---- 权限 ----

    @Test
    @Tag("mc")
    void everyPlayerReadsAndWritesOnlyHisOwnRulesNotEvenAnAdminSeesAnothers(@TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.dwinovo.numen.api.FakeWorld.boot(), "Minecraft 引导不可用");
        Libraries libraries = libraries(dir);

        Libraries.Result added = libraries.handle(PLAYER, false, Libraries.PERMISSION, Libraries.PUT,
                body("table", "deny", "rule", "break(owner & #c:chests)"));

        assertNull(added.problem());
        assertTrue(added.entries().contains("break(owner & #c:chests)"), "他自己读到刚加的");
        assertFalse(libraries.view(OTHER, false, Libraries.PERMISSION).contains("#c:chests"), "别人读不到");
        assertFalse(libraries.view(ADMIN, true, Libraries.PERMISSION).contains("#c:chests"), "管理员也读不到");
        assertNull(libraries.handle(OTHER, false, Libraries.PERMISSION, Libraries.RESET, new JsonObject()).problem());
        assertTrue(libraries.view(PLAYER, false, Libraries.PERMISSION).contains("#c:chests"), "别人清空动不了他的");
        assertNotNull(libraries.handle(OTHER, false, Libraries.PERMISSION, Libraries.DELETE,
                body("table", "deny", "rule", "break(owner & #c:chests)")).problem(), "别人删不到他的行");
        assertTrue(libraries.view(PLAYER, false, Libraries.PERMISSION).contains("#c:chests"));
    }

    @Test
    @Tag("mc")
    void aMistakenRuleIsRefusedWithTheCodeThatTheClientTranslates(@TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.dwinovo.numen.api.FakeWorld.boot(), "Minecraft 引导不可用");
        Libraries libraries = libraries(dir);

        Libraries.Result refused = libraries.handle(PLAYER, false, Libraries.PERMISSION, Libraries.PUT,
                body("table", "deny", "rule", "dig(owner)"));

        assertNotNull(refused.problem());
        JsonArray rows = array(refused.entries());
        JsonObject mistake = rows.get(rows.size() - 1).getAsJsonObject().getAsJsonObject("mistake");
        assertEquals("verb", mistake.get("code").getAsString());
        assertEquals("dig", mistake.getAsJsonArray("args").get(0).getAsString(), "原话也带着,界面填进译文");
        assertFalse(libraries.view(PLAYER, false, Libraries.PERMISSION).contains("dig("), "写错的没进表");
    }

    // ---- 服务器配置、偏好、外接大脑 ----

    @Test
    void theServerConfigIsAdminOnlyBothToReadAndToChange(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertEquals("[]", libraries.view(PLAYER, false, Libraries.SERVER_CONFIG));
        assertNotNull(libraries.handle(PLAYER, false, Libraries.SERVER_CONFIG, Libraries.PUT,
                body("proxy", "evil:1")).problem());
        assertEquals("", config.get("proxy"));

        assertNull(libraries.handle(ADMIN, true, Libraries.SERVER_CONFIG, Libraries.PUT, body("proxy", "10.0.0.1:7890"))
                .problem());
        assertEquals("10.0.0.1:7890", config.get("proxy"));
        assertEquals("", config.get("prompt"), "没带的项不动");
    }

    @Test
    void everyonesPrefsAreTheirOwn(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertNull(libraries.handle(PLAYER, false, Libraries.PREFS, Libraries.PUT, bodyInt("initiative", 8)).problem());

        assertEquals(8, array(libraries.view(PLAYER, false, Libraries.PREFS)).get(0).getAsJsonObject()
                .get("initiative").getAsInt());
        assertEquals(com.dwinovo.numen.api.agent.inbox.EventQueue.DEFAULT_LEVEL,
                array(libraries.view(OTHER, false, Libraries.PREFS)).get(0).getAsJsonObject().get("initiative").getAsInt(),
                "别人的偏好不受影响");
    }

    @Test
    void theExternalBrainIsAnAdminsAloneToSeeAndChange(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertEquals("[]", libraries.view(PLAYER, false, Libraries.MCP_SERVER));
        assertNotNull(libraries.handle(PLAYER, false, Libraries.MCP_SERVER, Libraries.PUT, body("token", "mine")).problem());
        assertEquals(1, array(libraries.view(ADMIN, true, Libraries.MCP_SERVER)).size());
    }

    @Test
    void anUnknownLibraryIsRefused(@TempDir Path dir) {
        assertNotNull(libraries(dir).handle(ADMIN, true, "elsewhere", Libraries.LIST, new JsonObject()).problem());
    }

    // ---- 人设:内置与我的两组 ----

    @Test
    void aPersonaLibraryHasTheBuiltinGroupAndTheOwnersGroupSideBySide(@TempDir Path dir) throws IOException {
        Libraries libraries = libraries(dir);
        write(dir.resolve("server").resolve("persona").resolve("numen").resolve("小焰.md"), "傲娇\n");
        hub.builtin(PersonaLibrary.KIND).reload();

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("name", "我的人设", "text", "活泼")).problem());

        String seen = libraries.view(PLAYER, false, Libraries.PERSONA);
        assertEquals("傲娇", row(seen, "numen:小焰", "builtin").get("text").getAsString());
        assertEquals("活泼", row(seen, "my:我的人设", "mine").get("text").getAsString());
        assertNull(row(libraries.view(OTHER, false, Libraries.PERSONA), "my:我的人设", "mine"), "别人的库里没有我的");
        assertNotNull(row(libraries.view(OTHER, false, Libraries.PERSONA), "numen:小焰", "builtin"), "内置的人人都有");
    }

    @Test
    void aBuiltinPersonaIsReadOnlyForPlayersWhoCopyItToMineAndEditTheCopy(@TempDir Path dir) throws IOException {
        Libraries libraries = libraries(dir);
        write(dir.resolve("server").resolve("persona").resolve("numen").resolve("小焰.md"), "傲娇\n");
        hub.builtin(PersonaLibrary.KIND).reload();

        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("id", "numen:小焰", "name", "小焰", "text", "改", "group", "builtin")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DELETE,
                body("id", "numen:小焰", "group", "builtin")).problem());

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.COPY, body("id", "numen:小焰")).problem());
        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("id", "numen:小焰", "name", "小焰", "text", "我改的")).problem());

        String seen = libraries.view(PLAYER, false, Libraries.PERSONA);
        assertEquals("我改的", row(seen, "numen:小焰", "mine").get("text").getAsString());
        assertTrue(row(seen, "numen:小焰", "mine").get("paired").getAsBoolean(), "我的盖着内置的");
        assertTrue(row(seen, "numen:小焰", "builtin").get("paired").getAsBoolean());
        assertEquals("傲娇\n", Files.readString(dir.resolve("server").resolve("persona").resolve("numen").resolve("小焰.md")),
                "服务器目录原样");
        assertEquals("傲娇", row(libraries.view(OTHER, false, Libraries.PERSONA), "numen:小焰", "builtin")
                .get("text").getAsString(), "别人看到的还是内置的");
    }

    @Test
    void theSwitchOnABuiltinEntryIsThePlayersOwn(@TempDir Path dir) throws IOException {
        Libraries libraries = libraries(dir);
        write(dir.resolve("server").resolve("persona").resolve("numen").resolve("小焰.md"), "傲娇\n");
        hub.builtin(PersonaLibrary.KIND).reload();

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DISABLE, body("id", "numen:小焰")).problem());

        assertFalse(row(libraries.view(PLAYER, false, Libraries.PERSONA), "numen:小焰", "builtin")
                .get("enabled").getAsBoolean());
        assertTrue(row(libraries.view(OTHER, false, Libraries.PERSONA), "numen:小焰", "builtin")
                .get("enabled").getAsBoolean(), "别人的不受影响");
        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.ENABLE, body("id", "numen:小焰")).problem());
        assertTrue(row(libraries.view(PLAYER, false, Libraries.PERSONA), "numen:小焰", "builtin")
                .get("enabled").getAsBoolean());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DISABLE, body("id", "numen:没有")).problem());
    }

    @Test
    void aPersonaIsEditedByItsIdRenamedAndDeletedByItsId(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT, body("name", "甲", "text", "一"));

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("id", "my:甲", "name", "甲", "text", "改过")).problem());
        assertTrue(libraries.view(PLAYER, false, Libraries.PERSONA).contains("改过"));

        Libraries.Result renamed = libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("id", "my:甲", "name", "乙", "text", "改过"));
        assertNull(row(renamed.entries(), "my:甲", "mine"));
        assertNotNull(row(renamed.entries(), "my:乙", "mine"));

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DELETE, body("id", "my:乙")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DELETE, body("id", "my:乙")).problem(),
                "删没有的是说不清的请求");
    }

    @Test
    void anAdminTakesOrDismissesTheNewVersionOfAModifiedBuiltinEntryAndPlayersCannot(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("jar");
        Path file = dir.resolve("server").resolve("persona").resolve("numen").resolve("小焰.md");
        List<Shipped.Bundle> bundles = List.of(new Shipped.Bundle("numen", jar));
        write(jar.resolve("persona").resolve("numen").resolve("小焰.md"), "v1\n");
        LibraryHub.headless(dir, bundles);
        write(file, "管理员的\n");                                            // 管理员改过
        write(jar.resolve("persona").resolve("numen").resolve("小焰.md"), "v2\n");   // 模组升级了
        Libraries libraries = libraries(dir, LibraryHub.headless(dir, bundles));   // 服务器重启

        JsonObject row = row(libraries.view(ADMIN, true, Libraries.PERSONA), "numen:小焰", "builtin");
        assertTrue(row.get("modified").getAsBoolean() && row.get("update").getAsBoolean(), row.toString());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.ACCEPT, body("id", "numen:小焰")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DISMISS, body("id", "numen:小焰")).problem());

        Libraries.Result accepted = libraries.handle(ADMIN, true, Libraries.PERSONA, Libraries.ACCEPT, body("id", "numen:小焰"));
        assertNull(accepted.problem());
        assertTrue(accepted.shared());
        assertEquals("v2", row(accepted.entries(), "numen:小焰", "builtin").get("text").getAsString(), "界面读到的是换进去的新版");
        assertFalse(row(accepted.entries(), "numen:小焰", "builtin").get("update").getAsBoolean());
        assertFalse(row(accepted.entries(), "numen:小焰", "builtin").get("modified").getAsBoolean());

        write(file, "管理员又改的\n");
        write(jar.resolve("persona").resolve("numen").resolve("小焰.md"), "v3\n");
        Libraries restarted = libraries(dir, LibraryHub.headless(dir, bundles));
        assertNull(restarted.handle(ADMIN, true, Libraries.PERSONA, Libraries.DISMISS, body("id", "numen:小焰")).problem());
        Libraries again = libraries(dir, LibraryHub.headless(dir, bundles));
        JsonObject kept = row(again.view(ADMIN, true, Libraries.PERSONA), "numen:小焰", "builtin");
        assertEquals("管理员又改的", kept.get("text").getAsString());
        assertTrue(kept.get("modified").getAsBoolean());
        assertFalse(kept.get("update").getAsBoolean(), "不采用的这一版不再提示");
    }

    // ---- 技能 ----

    @Test
    void anyPlayerSwitchesASkillOffAndOnAndRescansButSkillsAreNotEditedThroughTheProtocol(@TempDir Path dir)
            throws IOException {
        Libraries libraries = libraries(dir);
        assertEquals("[]", libraries.view(PLAYER, false, Libraries.SKILL), "服务器目录里还没有技能");
        write(dir.resolve("server").resolve("skills").resolve("gt").resolve("lumber").resolve(SkillRegistry.SKILL_FILENAME),
                "---\nname: lumber\ndescription: Fell a tree.\n---\n\n# Felling\n");

        assertFalse(libraries.view(PLAYER, false, Libraries.SKILL).contains("lumber"), "没重扫之前看不到");
        assertNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.RELOAD, new JsonObject()).problem());
        JsonObject lumber = row(libraries.view(PLAYER, false, Libraries.SKILL), "gt:lumber", "builtin");
        assertEquals("lumber", lumber.get("skill").getAsString());
        assertEquals("Fell a tree.", lumber.get("description").getAsString());

        assertNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.DISABLE, body("id", "gt:lumber")).problem());
        assertFalse(row(libraries.view(PLAYER, false, Libraries.SKILL), "gt:lumber", "builtin").get("enabled").getAsBoolean());
        assertTrue(row(libraries.view(OTHER, false, Libraries.SKILL), "gt:lumber", "builtin").get("enabled").getAsBoolean());
        assertNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.ENABLE, body("id", "gt:lumber")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.ENABLE, body("id", "gt:nope")).problem(),
                "没有这个技能才是没有");
        assertNotNull(libraries.handle(ADMIN, true, Libraries.SKILL, Libraries.PUT, body("name", "x", "text", "y")).problem(),
                "技能是目录,不经协议存");
    }

    @Test
    void copyingASkillToMineCopiesItsWholeDirectory(@TempDir Path dir) throws IOException {
        Libraries libraries = libraries(dir);
        Path skill = dir.resolve("server").resolve("skills").resolve("gt").resolve("lumber");
        write(skill.resolve(SkillRegistry.SKILL_FILENAME), "---\nname: lumber\ndescription: d\n---\nbody\n");
        write(skill.resolve("references").resolve("trees.md"), "oak");
        libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.RELOAD, new JsonObject());

        assertNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.COPY, body("id", "gt:lumber")).problem());

        Path mine = dir.resolve("players").resolve(PLAYER.toString()).resolve("skills").resolve("gt").resolve("lumber");
        assertEquals("oak", Files.readString(mine.resolve("references").resolve("trees.md")));
        assertNotNull(row(libraries.view(PLAYER, false, Libraries.SKILL), "gt:lumber", "mine"));
        assertNull(row(libraries.view(OTHER, false, Libraries.SKILL), "gt:lumber", "mine"));
    }

    // ---- 接进来的 MCP 服务器 ----

    private static final String STDIO = "{\"type\":\"stdio\",\"command\":\"secret-cmd\",\"args\":[\"--token\",\"hunter2\"],"
            + "\"enabled\":false}";
    private static final String HTTP = "{\"type\":\"http\",\"url\":\"http://internal.example/mcp\","
            + "\"headers\":{\"Authorization\":\"Bearer hunter2\"},\"enabled\":false}";

    private Libraries withMcpServers(Path dir) throws IOException {
        Libraries libraries = libraries(dir);
        write(dir.resolve("server").resolve("mcp").resolve("server").resolve("shell.json"), STDIO);
        write(dir.resolve("server").resolve("mcp").resolve("server").resolve("files.json"), HTTP);
        hub.builtin(McpClientConfig.KIND).reload();
        return libraries;
    }

    @Test
    void anOrdinaryPlayerSeesOnlyTheNameAndWhetherABuiltinMcpServerIsUp(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        String seen = libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.LIST, new JsonObject()).entries();

        JsonObject shell = row(seen, "server:shell", "builtin");
        assertEquals("shell", shell.get("name").getAsString());
        assertFalse(shell.get("connected").getAsBoolean());
        assertEquals(0, shell.get("toolCount").getAsInt());
        assertFalse(shell.has("spec"));
        assertFalse(seen.contains("secret-cmd") || seen.contains("hunter2") || seen.contains("internal.example"), seen);
    }

    @Test
    void anAdminSeesTheWholeBuiltinMcpServerAndAnOrdinaryPlayerCannotChangeIt(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        String seen = libraries.view(ADMIN, true, Libraries.MCP_CLIENT);
        assertTrue(seen.contains("secret-cmd") && seen.contains("internal.example") && seen.contains("DISABLED"), seen);

        for (String op : new String[]{Libraries.PUT, Libraries.DELETE, Libraries.CONNECT, Libraries.DISCONNECT}) {
            assertNotNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, op,
                    body("id", "server:shell", "group", "builtin", "name", "shell", "type", "http", "url", "x")).problem(), op);
        }
        assertEquals(2, array(libraries.view(ADMIN, true, Libraries.MCP_CLIENT)).size(), "什么也没变");
    }

    @Test
    void aPlayerAddsAnHttpMcpServerOfHisOwnButNeverAStdioOne(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        assertNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.PUT,
                body("name", "docs", "type", "http", "url", "http://127.0.0.1:1/mcp", "enabled", "false")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.PUT,
                body("name", "evil", "type", "stdio", "command", "rm")).problem());
        // 也不能把内置的 stdio 那台复制到自己名下再让服务器去起它
        assertNotNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.COPY, body("id", "server:shell")).problem());

        String seen = libraries.view(PLAYER, false, Libraries.MCP_CLIENT);
        assertNotNull(row(seen, "my:docs", "mine"));
        assertNull(row(seen, "my:evil", "mine"));
        assertNull(row(seen, "server:shell", "mine"));
        assertEquals("http://127.0.0.1:1/mcp", row(seen, "my:docs", "mine").getAsJsonObject("spec").get("url").getAsString());
        assertNull(row(libraries.view(OTHER, false, Libraries.MCP_CLIENT), "my:docs", "mine"), "别人看不到他的");
    }

    @Test
    void anAdminMayAddAStdioServerAndConnectsAndDisconnectsABuiltinOne(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.PUT,
                body("name", "tools", "type", "stdio", "command", "npx", "group", "builtin", "enabled", "false")).problem());
        assertNotNull(row(libraries.view(ADMIN, true, Libraries.MCP_CLIENT), "server:tools", "builtin"));

        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.CONNECT,
                body("id", "server:files", "group", "builtin")).problem());
        assertTrue(row(libraries.view(ADMIN, true, Libraries.MCP_CLIENT), "server:files", "builtin").get("connect").getAsBoolean());
        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.DISCONNECT,
                body("id", "server:files", "group", "builtin")).problem());
        assertFalse(row(libraries.view(ADMIN, true, Libraries.MCP_CLIENT), "server:files", "builtin").get("connect").getAsBoolean());
        assertTrue(Files.readString(dir.resolve("server").resolve("mcp").resolve("server").resolve("files.json"))
                .contains("internal.example"), "连与不连只翻 enabled,别的原样");
    }

    @Test
    void anOrdinaryPlayerSwitchesOffABuiltinMcpServerForHimselfOnly(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        assertNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.DISABLE, body("id", "server:files")).problem());

        assertFalse(row(libraries.view(PLAYER, false, Libraries.MCP_CLIENT), "server:files", "builtin")
                .get("enabled").getAsBoolean());
        assertTrue(row(libraries.view(OTHER, false, Libraries.MCP_CLIENT), "server:files", "builtin")
                .get("enabled").getAsBoolean());
    }

    // ---- 型号目录与连通性检测 ----

    /** 档案表单问目录的问题:{@code draft} 是草稿(一条档案的样子),{@code models} 要不要这个厂家的型号。 */
    private static JsonObject ask(boolean models, String... draftKeyValues) {
        JsonObject o = new JsonObject();
        o.add("draft", body(draftKeyValues));
        o.addProperty("models", models);
        return o;
    }

    @Test
    void theCatalogAnswersAnyPlayerWithItsVendorsModelsAndThePreview(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result answered = libraries.handle(PLAYER, false, Libraries.CATALOG, Libraries.LIST,
                ask(true, "provider", "deepseek", "model", "deepseek-chat"));
        assertNull(answered.problem());
        JsonObject answer = array(answered.entries()).get(0).getAsJsonObject();
        JsonObject first = answer.getAsJsonArray("vendors").get(0).getAsJsonObject();
        assertTrue(first.get("popular").getAsBoolean(), "常用的厂家排在前面");
        assertFalse(first.get("defaultModel").getAsString().isEmpty());
        assertEquals("deepseek", answer.get("modelsFor").getAsString());
        assertFalse(answer.getAsJsonArray("models").isEmpty(), "这个厂家的型号");
        JsonObject preview = answer.getAsJsonObject("preview");
        assertFalse(preview.get("baseUrl").getAsString().isEmpty(), "厂家的缺省地址");
        assertEquals("auto", preview.getAsJsonArray("steps").get(0).getAsJsonObject().get("level").getAsString(),
                "思考计划第一档永远是自动");

        var read = com.dwinovo.numen.api.client.agent.ServerLibraries.catalogOf(answer);
        assertTrue(read.vendors().get(0).popular());
        assertEquals("deepseek", read.modelsFor());
        assertEquals(answer.getAsJsonArray("models").size(), read.models().size());
        assertEquals(com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO, read.preview().steps().get(0).level());
        assertEquals(preview.get("baseUrl").getAsString(), read.preview().baseUrl());

        JsonObject withoutModels = array(libraries.handle(PLAYER, false, Libraries.CATALOG, Libraries.LIST,
                ask(false, "provider", "deepseek")).entries()).get(0).getAsJsonObject();
        assertFalse(withoutModels.has("models"), "没要型号就不送");
        assertFalse(array(libraries.view(PLAYER, false, Libraries.CATALOG)).get(0).getAsJsonObject().has("preview"),
                "没带草稿就没有解析");
    }

    @Test
    void aConnectionTestSaysWhatIsMissingBeforeItSpendsARequest(@TempDir Path dir) throws Exception {
        Libraries libraries = libraries(dir);

        // 没有 key:草稿没带、玩家钥匙串里没有、服主的钥匙串里也没有这个厂家的
        Libraries.Result noKey = libraries.handle(PLAYER, false, Libraries.PROVIDER_CHECK, Libraries.RUN,
                ask(false, "provider", "moonshotai"));
        assertNull(noKey.problem());
        assertEquals("checking", array(noKey.entries()).get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("need_key", array(noKey.later().get()).get(0).getAsJsonObject().get("state").getAsString());

        JsonObject typed = ask(false, "provider", "custom");
        typed.addProperty("key", "sk-x");
        Libraries.Result noUrl = libraries.handle(PLAYER, false, Libraries.PROVIDER_CHECK, Libraries.RUN, typed);
        assertEquals("need_url", array(noUrl.later().get()).get(0).getAsJsonObject().get("state").getAsString());
    }

    // ---- 声线与皮肤 ----

    @Test
    void aVoiceIsAnEntryWithoutAKeyAndOnlyAdminsWriteTheBuiltinOnes(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        JsonObject voice = body("name", "派蒙", "backend", "minimax", "service", "minimax-cn", "voice", "paimon",
                "api_key", "sk-never-stored");
        assertNull(libraries.handle(PLAYER, false, Libraries.VOICE, Libraries.PUT, voice).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.VOICE, Libraries.PUT,
                body("name", "全服的", "group", "builtin")).problem(), "玩家不能写内置");
        voice.addProperty("group", "builtin");
        Libraries.Result added = libraries.handle(ADMIN, true, Libraries.VOICE, Libraries.PUT, voice);
        assertNull(added.problem());
        assertTrue(added.shared());

        JsonObject mine = row(libraries.view(PLAYER, false, Libraries.VOICE), "my:派蒙", "mine");
        assertEquals("minimax", mine.get("backend").getAsString());
        assertEquals("minimax-cn", mine.get("service").getAsString());
        assertEquals("paimon", mine.get("voice").getAsString());
        assertFalse(libraries.view(PLAYER, false, Libraries.VOICE).contains("sk-never-stored"), "声线条目里没有 key");
        assertNotNull(row(libraries.view(PLAYER, false, Libraries.VOICE), "server:派蒙", "builtin"), "玩家看得到内置");
    }

    @Test
    void renamingAVoiceMovesTheEntry(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        libraries.handle(PLAYER, false, Libraries.VOICE, Libraries.PUT, body("name", "旧", "backend", "openai"));
        Libraries.Result renamed = libraries.handle(PLAYER, false, Libraries.VOICE, Libraries.PUT,
                body("id", "my:旧", "name", "新", "backend", "openai"));
        assertNull(renamed.problem());
        assertNull(row(renamed.entries(), "my:旧", "mine"));
        assertNotNull(row(renamed.entries(), "my:新", "mine"));
    }

    @Test
    void aSkinIsQueuedFirstAndOnlyBecomesAnEntryOnceSigned(@TempDir Path dir) throws Exception {
        Libraries libraries = libraries(dir);
        JsonObject put = body("name", "小红", "variant", "slim");
        put.addProperty("png", java.util.Base64.getEncoder().encodeToString(PNG_64));

        Libraries.Result queued = libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT, put);
        assertNull(queued.problem());
        assertTrue(signed.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS), "签名队没有把它签完");

        JsonObject entry = row(libraries.view(PLAYER, false, Libraries.SKIN), "my:小红", "mine");
        assertEquals("slim", entry.get("variant").getAsString());
        assertTrue(entry.get("signed").getAsBoolean());
        assertEquals(java.util.Base64.getEncoder().encodeToString(PNG_64), entry.get("png").getAsString(), "条目存着原图");
        assertEquals("v-slim", SkinProfile.read(hub.library(SkinProfile.KIND, PLAYER).find(EntryId.mine("小红")).text()).value());
    }

    @Test
    void aRefusedImageNeverGetsInTheQueue(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        JsonObject wrongSize = body("name", "大图");
        wrongSize.addProperty("png", java.util.Base64.getEncoder().encodeToString(pngHeader(128, 128)));

        assertNotNull(libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT, wrongSize).problem());
        assertTrue(signer.pendingOf(PLAYER).isEmpty());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT, body("name", "空")).problem(),
                "新皮肤要有图、网址或正版名");
        assertNotNull(libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT,
                body("name", "全服", "group", "builtin", "player", "Notch")).problem(), "玩家不能写内置");
    }

    @Test
    void aBorrowedPlayerSkinGoesThroughTheSameQueue(@TempDir Path dir) throws Exception {
        Libraries libraries = libraries(dir);
        assertNull(libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT,
                body("name", "借来的", "player", "Notch")).problem());
        assertTrue(signed.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
        SkinProfile borrowed = SkinProfile.read(hub.library(SkinProfile.KIND, PLAYER).find(EntryId.mine("借来的")).text());
        assertEquals("mojang-value", borrowed.value(), "借来的本来就是 Mojang 签好的,不经 MineSkin");
    }

    @Test
    void renamingASignedSkinKeepsItsSignatureAndChangingTheArmsSignsItAgain(@TempDir Path dir) throws Exception {
        Libraries libraries = libraries(dir);
        JsonObject put = body("name", "原名");
        put.addProperty("png", java.util.Base64.getEncoder().encodeToString(PNG_64));
        libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT, put);
        assertTrue(signed.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));

        Libraries.Result renamed = libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT,
                body("id", "my:原名", "name", "新名"));
        assertNull(renamed.problem());
        assertNotNull(row(renamed.entries(), "my:新名", "mine"));
        assertNull(row(renamed.entries(), "my:原名", "mine"));
        assertEquals("v-classic", SkinProfile.read(hub.library(SkinProfile.KIND, PLAYER).find(EntryId.mine("新名")).text())
                .value(), "只改名不重签");

        libraries.handle(PLAYER, false, Libraries.SKIN, Libraries.PUT, body("id", "my:新名", "name", "新名", "variant", "slim"));
        assertTrue(signed.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("v-slim", SkinProfile.read(hub.library(SkinProfile.KIND, PLAYER).find(EntryId.mine("新名")).text())
                .value(), "换了手臂模型,用存着的原图重签");
    }

    @Test
    void anAdminRestoresAModifiedBuiltinToTheJarsVersion(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("jar");
        Path file = dir.resolve("server").resolve("voice").resolve("numen").resolve("默认.json");
        List<Shipped.Bundle> bundles = List.of(new Shipped.Bundle("numen", jar));
        String shipped = new VoiceProfile("", "openai", "", "", "m", "alex", "", "", "", 1f).write();
        write(jar.resolve("voice").resolve("numen").resolve("默认.json"), shipped);
        LibraryHub.headless(dir, bundles);
        write(file, new VoiceProfile("", "openai", "", "", "m", "改过的音色", "", "", "", 1f).write());
        Libraries libraries = libraries(dir, LibraryHub.headless(dir, bundles));
        assertTrue(row(libraries.view(ADMIN, true, Libraries.VOICE), "numen:默认", "builtin").get("modified").getAsBoolean());

        assertNotNull(libraries.handle(PLAYER, false, Libraries.VOICE, Libraries.RESTORE, body("id", "numen:默认")).problem());
        Libraries.Result restored = libraries.handle(ADMIN, true, Libraries.VOICE, Libraries.RESTORE, body("id", "numen:默认"));

        assertNull(restored.problem());
        assertTrue(restored.shared());
        JsonObject row = row(restored.entries(), "numen:默认", "builtin");
        assertEquals("alex", row.get("voice").getAsString());
        assertFalse(row.get("modified").getAsBoolean());
    }

    @Test
    void aKeyForTheSkinSignerIsOfferedToAdminsOnly(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        assertTrue(libraries.keyServices(ADMIN, true).contains(MineSkinClient.KEY_SERVICE));
        assertFalse(libraries.keyServices(PLAYER, false).contains(MineSkinClient.KEY_SERVICE));
    }

    private static JsonObject bodyInt(String key, int value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }
}
