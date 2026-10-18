package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/** 谁能读写哪种库、能看到什么:判据只在这一处,脱敏发生在服务端编码的地方。 */
class LibrariesTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final Map<String, String> config = new HashMap<>(Map.of("proxy", "", "prompt", ""));
    private final Map<UUID, PersonaLibrary> personas = new HashMap<>();
    private final Map<UUID, OwnerPrefs> prefs = new HashMap<>();

    private Libraries libraries(Path dir) {
        ProviderLibrary providers = new ProviderLibrary(dir.resolve("providers.json"),
                dir.resolve("cache").resolve("models-dev.json"), Io.direct(e -> { }), () -> "");
        providers.create("服主的 DeepSeek", new ConnectionProfile("deepseek", null, "deepseek-chat", "sk-secret",
                "https://relay.example", "127.0.0.1:7890", null, 0, null, 0, null));
        return new Libraries(owner -> personas.computeIfAbsent(owner,
                id -> PersonaLibrary.at(dir.resolve("players").resolve(id.toString()).resolve("persona"),
                        Io.direct(e -> { }))),
                owner -> prefs.computeIfAbsent(owner, id -> new OwnerPrefs(new com.dwinovo.numen.api.store.JsonDoc(
                        dir.resolve("players").resolve(id.toString()).resolve("prefs.json"),
                        com.dwinovo.numen.api.store.JsonDoc.Format.of(1), Io.direct(e -> { })))),
                owner -> List.of(), providers, new Libraries.ServerConfig() {
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
        });
    }

    @AfterEach
    void forgetMcpServers() {
        McpClientManager.shutdown();
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

    @Test
    void anOrdinaryPlayerSeesWhichProfilesThereAreButNeverAKeyAnAddressOrAProxy(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        String seen = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.LIST, new JsonObject()).entries();

        JsonObject profile = array(seen).get(0).getAsJsonObject();
        assertEquals("服主的 DeepSeek", profile.get("name").getAsString());
        assertEquals("deepseek-chat", profile.get("model").getAsString());
        assertFalse(seen.contains("sk-secret") || seen.contains("relay.example") || seen.contains("7890"), seen);
    }

    @Test
    void anAdminSeesTheWholeProfile(@TempDir Path dir) {
        String seen = libraries(dir).handle(ADMIN, true, Libraries.PROVIDER, Libraries.LIST, new JsonObject()).entries();

        assertTrue(seen.contains("sk-secret") && seen.contains("relay.example"));
    }

    @Test
    void anOrdinaryPlayerCannotChangeAProfileAndSeesTheTableUnchanged(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        String id = array(libraries.view(ADMIN, true, Libraries.PROVIDER)).get(0).getAsJsonObject().get("id").getAsString();

        Libraries.Result put = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.PUT,
                body("name", "我的", "provider", "openai", "api_key", "sk-mine"));
        Libraries.Result delete = libraries.handle(PLAYER, false, Libraries.PROVIDER, Libraries.DELETE, body("id", id));

        assertNotNull(put.problem());
        assertNotNull(delete.problem());
        assertEquals(1, array(libraries.view(ADMIN, true, Libraries.PROVIDER)).size(), "什么也没变");
        assertFalse(put.entries().contains("sk-mine"));
    }

    @Test
    void anAdminCanAddEditAndDeleteAProfile(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result added = libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.PUT,
                body("name", "第二个", "provider", "openai", "model", "gpt", "api_key", "sk-2"));
        assertNull(added.problem());
        JsonObject second = null;
        for (var e : array(added.entries())) {
            if (e.getAsJsonObject().get("name").getAsString().equals("第二个")) {
                second = e.getAsJsonObject();
            }
        }
        assertNotNull(second);

        second.addProperty("model", "gpt-5");
        assertNull(libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.PUT, second).problem());
        assertTrue(libraries.view(ADMIN, true, Libraries.PROVIDER).contains("gpt-5"));

        assertNull(libraries.handle(ADMIN, true, Libraries.PROVIDER, Libraries.DELETE,
                body("id", second.get("id").getAsString())).problem());
        assertEquals(1, array(libraries.view(ADMIN, true, Libraries.PROVIDER)).size());
    }

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
    void everyonesPersonasAreTheirOwn(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("name", "我的人设", "text", "活泼")).problem());

        assertTrue(libraries.view(PLAYER, false, Libraries.PERSONA).contains("我的人设"));
        assertFalse(libraries.view(OTHER, false, Libraries.PERSONA).contains("我的人设"), "别人的库里没有");
    }

    @Test
    void aPersonaIsEditedByItsIdAndDeletedByItsId(@TempDir Path dir) {
        Libraries libraries = libraries(dir);
        libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT, body("name", "甲", "text", "一"));

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.PUT,
                body("id", "甲", "name", "甲", "text", "改过")).problem());
        assertTrue(libraries.view(PLAYER, false, Libraries.PERSONA).contains("改过"));

        assertNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DELETE, body("id", "甲")).problem());
        assertNotNull(libraries.handle(PLAYER, false, Libraries.PERSONA, Libraries.DELETE, body("id", "甲")).problem(),
                "删没有的是说不清的请求");
    }

    @Test
    void anUnknownLibraryIsRefused(@TempDir Path dir) {
        assertNotNull(libraries(dir).handle(ADMIN, true, "elsewhere", Libraries.LIST, new JsonObject()).problem());
    }

    private Libraries withMcpServers(Path dir) throws java.io.IOException {
        java.nio.file.Files.writeString(dir.resolve("mcp_clients.json"), """
                {"enabled":true,"servers":[
                  {"name":"shell","type":"stdio","command":"secret-cmd","args":["--token","hunter2"],"env":{},"enabled":false},
                  {"name":"files","type":"http","url":"http://internal.example/mcp","headers":{"Authorization":"Bearer hunter2"},"enabled":false}
                ]}""");
        McpClientManager.init(dir, Runnable::run, null);
        return libraries(dir);
    }

    @Test
    void anOrdinaryPlayerSeesOnlyTheNameAndWhetherAnMcpServerIsUp(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        String seen = libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, Libraries.LIST, new JsonObject()).entries();

        JsonObject shell = array(seen).get(0).getAsJsonObject();
        assertEquals("shell", shell.get("name").getAsString());
        assertFalse(shell.get("connected").getAsBoolean());
        assertEquals(0, shell.get("toolCount").getAsInt());
        assertFalse(seen.contains("secret-cmd") || seen.contains("hunter2") || seen.contains("internal.example"), seen);
    }

    @Test
    void anAdminSeesTheWholeMcpServerAndAnOrdinaryPlayerCannotChangeAnything(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        String seen = libraries.view(ADMIN, true, Libraries.MCP_CLIENT);
        assertTrue(seen.contains("secret-cmd") && seen.contains("internal.example") && seen.contains("DISABLED"), seen);

        for (String op : new String[]{Libraries.PUT, Libraries.DELETE, Libraries.ENABLE, Libraries.DISABLE, Libraries.AUTH}) {
            assertNotNull(libraries.handle(PLAYER, false, Libraries.MCP_CLIENT, op,
                    body("name", "shell", "type", "stdio", "command", "evil", "url", "x")).problem(), op);
        }
        assertEquals(2, array(libraries.view(ADMIN, true, Libraries.MCP_CLIENT)).size(), "什么也没变");
        assertFalse(libraries.view(ADMIN, true, Libraries.MCP_CLIENT).contains("evil"));
    }

    @Test
    void anAdminAddsRenamesAndDeletesAnMcpServer(@TempDir Path dir) throws Exception {
        Libraries libraries = withMcpServers(dir);

        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.PUT,
                body("name", "docs", "type", "http", "url", "http://127.0.0.1:1/mcp")).problem());
        JsonObject renamed = body("name", "manual", "type", "http", "url", "http://127.0.0.1:1/mcp", "replaces", "docs");
        renamed.addProperty("enabled", false);
        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.PUT, renamed).problem());
        String seen = libraries.view(ADMIN, true, Libraries.MCP_CLIENT);
        assertTrue(seen.contains("manual") && !seen.contains("\"docs\""), seen);

        assertNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.DELETE, body("name", "manual")).problem());
        assertNotNull(libraries.handle(ADMIN, true, Libraries.MCP_CLIENT, Libraries.DELETE, body("name", "manual")).problem());
        assertEquals(2, array(libraries.view(ADMIN, true, Libraries.MCP_CLIENT)).size());
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
    void theSkillDirectoryIsTheServersAndOnlyAnAdminMakesItRescan(@TempDir Path dir) throws java.io.IOException {
        Libraries libraries = libraries(dir);
        Path skills = dir.resolve("skills");
        java.nio.file.Files.createDirectories(skills);
        com.dwinovo.numen.api.agent.skill.SkillRegistry registry = com.dwinovo.numen.api.agent.skill.SkillRegistry.instance();
        try {
            registry.scan(skills);
            Path lumber = java.nio.file.Files.createDirectories(skills.resolve("lumber"));
            java.nio.file.Files.writeString(lumber.resolve(com.dwinovo.numen.api.agent.skill.SkillRegistry.SKILL_FILENAME),
                    "---\nname: lumber\ndescription: Fell a tree.\n---\n\n# Felling\n");

            assertNotNull(libraries.handle(PLAYER, false, Libraries.SKILL, Libraries.RELOAD, new JsonObject()).problem());
            assertFalse(libraries.view(PLAYER, false, Libraries.SKILL).contains("lumber"), "没重扫之前看不到");

            assertNull(libraries.handle(ADMIN, true, Libraries.SKILL, Libraries.RELOAD, new JsonObject()).problem());
            assertTrue(libraries.view(PLAYER, false, Libraries.SKILL).contains("lumber"), "重扫之后人人看得到");
        } finally {
            registry.scan(null);
        }
    }

    @Test
    void aSkillSwitchedOffCanBeSwitchedOnAgainAndTheSwitchIsKept(@TempDir Path dir) throws java.io.IOException {
        Libraries libraries = libraries(dir);
        Path skills = dir.resolve("skills");
        Path lumber = java.nio.file.Files.createDirectories(skills.resolve("lumber"));
        java.nio.file.Files.writeString(lumber.resolve(com.dwinovo.numen.api.agent.skill.SkillRegistry.SKILL_FILENAME),
                "---\nname: lumber\ndescription: Fell a tree.\n---\n\n# Felling\n");
        com.dwinovo.numen.api.agent.skill.SkillRegistry registry = com.dwinovo.numen.api.agent.skill.SkillRegistry.instance();
        try {
            registry.scan(skills);
            JsonObject named = body("name", "lumber");
            Path state = dir.resolve("skills_state.json");

            assertNull(libraries.handle(ADMIN, true, Libraries.SKILL, Libraries.DISABLE, named).problem());
            assertTrue(java.nio.file.Files.readString(state).contains("lumber"), "关了就落盘");
            assertTrue(libraries.view(ADMIN, true, Libraries.SKILL).contains("\"enabled\":false"));
            assertTrue(registry.get("lumber").isEmpty(), "关掉的对 skill 工具不可见");

            assertNull(libraries.handle(ADMIN, true, Libraries.SKILL, Libraries.ENABLE, named).problem(),
                    "关掉的技能仍是已知的技能,能被重新打开");
            assertFalse(java.nio.file.Files.readString(state).contains("lumber"), "开了也落盘");
            assertTrue(libraries.view(ADMIN, true, Libraries.SKILL).contains("\"enabled\":true"));
            assertTrue(registry.get("lumber").isPresent());

            assertNotNull(libraries.handle(ADMIN, true, Libraries.SKILL, Libraries.ENABLE, body("name", "nope")).problem(),
                    "没有这个技能才是没有");
        } finally {
            registry.scan(null);
        }
    }

    /** 档案表单问目录的问题:{@code draft} 是草稿(一条档案的样子),{@code models} 要不要这个厂家的型号。 */
    private static JsonObject ask(boolean models, String... draftKeyValues) {
        JsonObject o = new JsonObject();
        o.add("draft", body(draftKeyValues));
        o.addProperty("models", models);
        return o;
    }

    @Test
    void theCatalogIsAnAdminsToAskAndTheServerAnswersWithItsVendorsModelsAndThePreview(@TempDir Path dir) {
        Libraries libraries = libraries(dir);

        Libraries.Result refused = libraries.handle(PLAYER, false, Libraries.CATALOG, Libraries.LIST, new JsonObject());
        assertNotNull(refused.problem());
        assertEquals("[]", refused.entries());

        Libraries.Result answered = libraries.handle(ADMIN, true, Libraries.CATALOG, Libraries.LIST,
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

        JsonObject withoutModels = array(libraries.handle(ADMIN, true, Libraries.CATALOG, Libraries.LIST,
                ask(false, "provider", "deepseek")).entries()).get(0).getAsJsonObject();
        assertFalse(withoutModels.has("models"), "没要型号就不送");
        assertFalse(array(libraries.view(ADMIN, true, Libraries.CATALOG)).get(0).getAsJsonObject().has("preview"),
                "没带草稿就没有解析");
    }

    @Test
    void aConnectionTestSaysWhatIsMissingBeforeItSpendsARequest(@TempDir Path dir) throws Exception {
        Libraries libraries = libraries(dir);

        assertNotNull(libraries.handle(PLAYER, false, Libraries.PROVIDER_CHECK, Libraries.RUN,
                ask(false, "provider", "deepseek", "api_key", "sk-x")).problem());

        Libraries.Result noKey = libraries.handle(ADMIN, true, Libraries.PROVIDER_CHECK, Libraries.RUN,
                ask(false, "provider", "deepseek"));
        assertNull(noKey.problem());
        assertEquals("checking", array(noKey.entries()).get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("need_key", array(noKey.later().get()).get(0).getAsJsonObject().get("state").getAsString());

        Libraries.Result noUrl = libraries.handle(ADMIN, true, Libraries.PROVIDER_CHECK, Libraries.RUN,
                ask(false, "provider", "custom", "api_key", "sk-x"));
        assertEquals("need_url", array(noUrl.later().get()).get(0).getAsJsonObject().get("state").getAsString());
    }

    private static JsonObject bodyInt(String key, int value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }
}
