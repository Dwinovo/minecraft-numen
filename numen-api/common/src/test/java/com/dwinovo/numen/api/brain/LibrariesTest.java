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
                dir.resolve("cache").resolve("models-dev.json"), Io.direct(e -> { }));
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

    private static JsonObject bodyInt(String key, int value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }
}
