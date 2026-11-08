package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧布局的库搬进新布局(单人游戏的房主):复制不移动、只做一次;模型档案进内置组、key 进服主的钥匙串,人设与技能与改过的模块进房主的
 * "我的",和内置一模一样的不搬,旧的开关换成房主的开关,同伴绑定里的旧名字换成新的。
 */
class LegacyLibrariesTest {

    private static void text(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    // ---- 模型档案 ----

    private static final String PROVIDERS = """
            {"v":2,"entries":[
              {"id":"prov_1","name":"我的 DeepSeek","provider":"deepseek","model":"deepseek-chat","api_key":"sk-aaa"},
              {"id":"prov_2","name":"备用","provider":"deepseek","model":"deepseek-reasoner","api_key":"sk-bbb"},
              {"id":"prov_3","name":"本地","provider":"openai","base_url":"http://localhost:1/v1"}]}""";

    @Test
    void oldProfilesBecomeBuiltinEntriesAndTheirKeysGoToTheServersKeychain(@TempDir Path dir) throws IOException {
        text(dir.resolve("config").resolve("providers.json"), PROVIDERS);
        Path server = dir.resolve("server");

        Map<String, String> ids = LegacyLibraries.providers(dir.resolve("config").resolve("providers.json"), server);

        assertEquals(Map.of("prov_1", "server:我的 DeepSeek", "prov_2", "server:备用", "prov_3", "server:本地"), ids);
        JsonObject first = json(server.resolve("provider").resolve("server").resolve("我的 DeepSeek.json"));
        assertEquals("deepseek-chat", first.get("model").getAsString());
        assertFalse(first.has("api_key"), "key 不在档案里");
        assertEquals("sk-aaa", Files.readString(server.resolve("keys").resolve("deepseek.key")),
                "同一个厂家几把不同的 key,留第一把");
        assertFalse(Files.exists(server.resolve("keys").resolve("openai.key")), "没填 key 的不建");
    }

    @Test
    void theOldProfilesFileOfTheFirstVersionIsUpgradedToModelsDevIdsOnTheWay(@TempDir Path dir) throws IOException {
        text(dir.resolve("config").resolve("providers.json"), """
                {"entries":[{"id":"p","name":"kimi","provider":"kimi","model":"k","api_key":"sk"},
                            {"id":"q","name":"ollama","provider":"ollama","model":"llama"}]}""");
        Path server = dir.resolve("server");

        LegacyLibraries.providers(dir.resolve("config").resolve("providers.json"), server);

        assertEquals("moonshotai", json(server.resolve("provider").resolve("server").resolve("kimi.json"))
                .get("provider").getAsString());
        JsonObject local = json(server.resolve("provider").resolve("server").resolve("ollama.json"));
        assertEquals("custom", local.get("provider").getAsString());
        assertEquals("http://localhost:11434/v1", local.get("base_url").getAsString());
        assertTrue(Files.exists(server.resolve("keys").resolve("moonshotai.key")));
    }

    @Test
    void movingProfilesTwiceChangesNothingAndStillAnswersTheSameIds(@TempDir Path dir) throws IOException {
        text(dir.resolve("config").resolve("providers.json"), PROVIDERS);
        Path server = dir.resolve("server");
        LegacyLibraries.providers(dir.resolve("config").resolve("providers.json"), server);
        text(server.resolve("provider").resolve("server").resolve("备用.json"), "{\"model\":\"管理员改过\"}");

        Map<String, String> again = LegacyLibraries.providers(dir.resolve("config").resolve("providers.json"), server);

        assertEquals("server:备用", again.get("prov_2"), "绑定还要用的对照表,迁过了也算得出来");
        assertEquals("{\"model\":\"管理员改过\"}", Files.readString(server.resolve("provider").resolve("server").resolve("备用.json")));
    }

    // ---- 人设 ----

    @Test
    void personasLikeABuiltinOneAreNotCopiedAndTheirBindingPointsAtTheBuiltin(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("server");
        text(server.resolve("persona").resolve("numen").resolve("小焰.md"), "傲娇\n");
        Path flat = dir.resolve("players").resolve("host").resolve("persona");
        text(flat.resolve("小焰.md"), "傲娇\n");
        text(flat.resolve("我的猫.md"), "喵\n");
        text(flat.resolve(".init"), "marker");

        Map<String, String> ids = LegacyLibraries.personas(flat, flat.resolve("my"), server);

        assertEquals(Map.of("小焰", "numen:小焰", "我的猫", "my:我的猫"), ids);
        assertEquals("喵\n", Files.readString(flat.resolve("my").resolve("我的猫.md")));
        assertFalse(Files.exists(flat.resolve("my").resolve("小焰.md")), "和内置一样的不搬");
        assertFalse(Files.exists(flat.resolve("my").resolve(".init")));
        assertTrue(Files.exists(flat.resolve("我的猫.md")), "旧的原样留着");
    }

    @Test
    void aPersonaTheOwnerEditedKeepsItsNameInMine(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("server");
        text(server.resolve("persona").resolve("numen").resolve("小焰.md"), "傲娇\n");
        Path flat = dir.resolve("players").resolve("host").resolve("persona");
        text(flat.resolve("小焰.md"), "改过的\n");

        Map<String, String> ids = LegacyLibraries.personas(flat, flat.resolve("my"), server);

        assertEquals("my:小焰", ids.get("小焰"));
        assertEquals("改过的\n", Files.readString(flat.resolve("my").resolve("小焰.md")));
    }

    // ---- 模块 ----

    @Test
    void installedFactoryModulesAreNotCopiedButChangedAndOwnOnesAre(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("server");
        text(server.resolve("modules").resolve("numen").resolve("work.lua"), "-- w\nreturn {}\n");
        text(server.resolve("modules").resolve("numen").resolve("move.lua"), "-- m\nreturn {}\n");
        Path old = dir.resolve("players").resolve("host").resolve("lua");
        text(old.resolve("numen").resolve("work.lua"), "-- w\nreturn {}\n");          // 出厂装进来的,没改
        text(old.resolve("numen").resolve("move.lua"), "-- m changed\nreturn {}\n");   // 改过
        text(old.resolve("my").resolve("lumber.lua"), "-- l\nreturn {}\n");            // 她自己的
        text(old.resolve("modules.json"), "{\"numen.move\":{\"factory\":\"abc\"}}");
        Path target = dir.resolve("players").resolve("host").resolve("modules");
        Switches switches = Switches.inMemory();

        LegacyLibraries.modules(old, target, server, switches);

        assertFalse(Files.exists(target.resolve("numen").resolve("work.lua")));
        assertEquals("-- m changed\nreturn {}\n", Files.readString(target.resolve("numen").resolve("move.lua")));
        assertTrue(Files.exists(target.resolve("my").resolve("lumber.lua")));
        assertTrue(Files.exists(target.resolve("modules.json")), "账本跟着模块");
    }

    @Test
    void aFactoryModuleSheDeletedBecomesHerSwitchOff(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("server");
        text(server.resolve("modules").resolve("numen").resolve("fight.lua"), "-- f\nreturn {}\n");
        Path old = dir.resolve("players").resolve("host").resolve("lua");
        text(old.resolve("modules.json"), "{\"numen.fight\":{\"factory\":\"abc\"}}");
        Files.createDirectories(old.resolve("my"));
        Switches switches = Switches.inMemory();

        LegacyLibraries.modules(old, dir.resolve("players").resolve("host").resolve("modules"), server, switches);

        assertFalse(switches.enabled("modules", new EntryId("numen", "fight")));
    }

    // ---- 技能 ----

    @Test
    void oldSkillsGoToMineAndTheOldGlobalSwitchesBecomeHers(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config");
        text(config.resolve("skills").resolve("lumber").resolve("SKILL.md"), "---\nname: lumber\ndescription: d\n---\nbody\n");
        text(config.resolve("skills_state.json"), "{\"v\":1,\"disabled\":[\"lumber\",\"mining_and_ores\"]}");
        Path server = dir.resolve("server");
        text(server.resolve("skills").resolve("numen").resolve("mining_and_ores").resolve("SKILL.md"),
                "---\nname: mining_and_ores\ndescription: d\n---\nbody\n");
        Path hostMy = dir.resolve("players").resolve("host").resolve("skills").resolve("my");
        Switches switches = Switches.inMemory();

        LegacyLibraries.skills(config.resolve("skills"), config.resolve("skills_state.json"), hostMy,
                server.resolve("skills"), switches);

        assertTrue(Files.exists(hostMy.resolve("lumber").resolve("SKILL.md")));
        assertFalse(switches.enabled("skills", new EntryId("my", "lumber")));
        assertFalse(switches.enabled("skills", new EntryId("numen", "mining_and_ores")));
        assertTrue(switches.enabled("skills", new EntryId("numen", "other")));
    }

    // ---- 接进来的 MCP 服务器 ----

    @Test
    void oldMcpServersBecomeBuiltinEntriesAndAMasterSwitchOffMeansNoneConnects(@TempDir Path dir) throws IOException {
        text(dir.resolve("config").resolve("mcp_clients.json"), """
                {"enabled":false,"servers":[
                  {"name":"kfc","type":"http","url":"http://x/mcp","enabled":true},
                  {"name":"files","type":"stdio","command":"npx","args":["-y","x"]}]}""");
        Path mcp = dir.resolve("server").resolve("mcp");

        LegacyLibraries.mcp(dir.resolve("config").resolve("mcp_clients.json"), mcp);

        JsonObject kfc = json(mcp.resolve("server").resolve("kfc.json"));
        assertEquals("http://x/mcp", kfc.get("url").getAsString());
        assertFalse(kfc.get("enabled").getAsBoolean(), "总开关关着,每台都写成不连");
        assertFalse(kfc.has("name"), "名字是文件名");
        assertEquals("npx", json(mcp.resolve("server").resolve("files.json")).get("command").getAsString());
    }

    // ---- 绑定 ----

    @Test
    void bindingsPointAtTheNewNamesAndANewerBindingIsLeftAlone(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("a").resolve("binding.json");
        Path current = dir.resolve("b").resolve("binding.json");
        Path none = dir.resolve("c").resolve("binding.json");
        write(old, "prov_1", "小焰");
        write(current, "server:备用", "numen:小焰");

        int changed = LegacyLibraries.bindings(List.of(old, current, none),
                Map.of("prov_1", "server:我的 DeepSeek"), Map.of("小焰", "numen:小焰"));

        assertEquals(1, changed);
        assertEquals("server:我的 DeepSeek", json(old).get("provider").getAsString());
        assertEquals("numen:小焰", json(old).get("persona").getAsString());
        assertEquals("server:备用", json(current).get("provider").getAsString());
    }

    @Test
    void aPersonaTheMigrationNeverSawStillGetsANamespace(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("binding.json");
        write(file, null, "不知道的");

        LegacyLibraries.bindings(List.of(file), Map.of(), Map.of());

        assertEquals("my:不知道的", json(file).get("persona").getAsString());
    }

    private static void write(Path file, String provider, String persona) {
        JsonObject binding = new JsonObject();
        if (provider != null) {
            binding.addProperty("provider", provider);
        }
        binding.addProperty("persona", persona);
        new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).write(binding);
    }
}
