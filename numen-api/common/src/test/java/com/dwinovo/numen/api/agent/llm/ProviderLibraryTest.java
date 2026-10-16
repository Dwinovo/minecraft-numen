package com.dwinovo.numen.api.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 档案库的存取:档案的每个字段落盘再读回来;第 1 版的库(厂家 id 还是各家的叫法)读时升到第 2 版。 */
class ProviderLibraryTest {

    @TempDir
    Path dir;

    private ProviderLibrary fresh() {
        return new ProviderLibrary(dir.resolve("providers.json"), dir.resolve("cache").resolve("models-dev.json"),
                com.dwinovo.numen.api.store.Io.direct(e -> { }));
    }

    @Test
    void everyProfileFieldSurvivesAReload() {
        JsonObject compat = JsonParser.parseString("{\"thinking_format\":\"none\"}").getAsJsonObject();
        ConnectionProfile profile = new ConnectionProfile("deepseek", "chat", "deepseek-v4-flash", "sk-1",
                "https://x.example/v1", "127.0.0.1:7890", "high", 32_000, 0.4, 2048, compat);
        ProviderLibrary.Entry e = fresh().create("我的 DeepSeek", profile);

        ProviderLibrary.Entry back = fresh().get(e.id());
        assertEquals("我的 DeepSeek", back.name());
        assertEquals(profile.provider(), back.profile().provider());
        assertEquals(profile.protocol(), back.profile().protocol());
        assertEquals(profile.model(), back.profile().model());
        assertEquals(profile.apiKey(), back.profile().apiKey());
        assertEquals(profile.baseUrl(), back.profile().baseUrl());
        assertEquals(profile.proxy(), back.profile().proxy());
        assertEquals(profile.reasoningEffort(), back.profile().reasoningEffort());
        assertEquals(32_000, back.profile().ctx());
        assertEquals(0.4, back.profile().temperature());
        assertEquals(2048, back.profile().maxTokens());
        assertEquals(compat, back.profile().compat());
    }

    @Test
    void anOldStyleEntryReadsWithTheNewFieldsUnset() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[{"id":"p1","name":"旧档案","provider":"deepseek","model":"deepseek-chat",
                  "api_key":"sk-old","reasoning_effort":"off","ctx":64000}]}""");
        ConnectionProfile p = fresh().get("p1").profile();
        assertEquals("deepseek", p.provider());
        assertEquals("off", p.reasoningEffort());
        assertEquals(64_000, p.ctx());
        assertNull(p.protocol());
        assertNull(p.temperature());
        assertEquals(0, p.maxTokens());
        assertNull(p.compat());
    }

    // ---- 厂家 id 迁到 models.dev:第 1 版的库读时升到第 2 版 ----

    private ProviderLibrary.Entry read(String id) {
        return fresh().get(id);
    }

    @Test
    void legacyProviderIdsBecomeModelsDevIds() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[
                  {"id":"a","provider":"moonshot","api_key":"k1","model":"kimi-k2"},
                  {"id":"b","provider":"Zhipu"},{"id":"c","provider":"dashscope"},{"id":"d","provider":"siliconflow"},
                  {"id":"e","provider":"gemini"},{"id":"f","provider":"grok"},{"id":"g","provider":"qwen"},
                  {"id":"h","provider":"deepseek"}]}""");
        String[] expected = {"moonshotai", "zhipuai", "alibaba-cn", "siliconflow-cn", "google", "xai",
                "alibaba-cn", "deepseek"};
        String[] ids = {"a", "b", "c", "d", "e", "f", "g", "h"};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(expected[i], read(ids[i]).profile().provider(), "entry " + ids[i]);
        }
        assertEquals("k1", read("a").profile().apiKey(), "别的字段原样保留");
        assertEquals("kimi-k2", read("a").profile().model());
    }

    @Test
    void localDeploymentsBecomeCustomProvidersKeepingTheirAddress() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[
                  {"id":"a","provider":"ollama","model":"qwen3"},
                  {"id":"b","provider":"lmstudio","base_url":"http://192.168.1.5:1234/v1"},
                  {"id":"c","provider":"vllm"}]}""");
        for (String id : new String[] {"a", "b", "c"}) {
            assertEquals("custom", read(id).profile().provider());
            assertEquals("chat", read(id).profile().protocol());
        }
        assertEquals("http://localhost:11434/v1", read("a").profile().baseUrl());
        assertEquals("http://192.168.1.5:1234/v1", read("b").profile().baseUrl(), "玩家填过的地址不动");
        assertEquals("http://localhost:8000/v1", read("c").profile().baseUrl());
        assertEquals("qwen3", read("a").profile().model());
    }

    @Test
    void aLibraryMarkedByTheOldMigrationIsNotMigratedAgain() throws IOException {
        // models.dev 自己也有 lmstudio:迁过之后玩家选了它,不能再被当成老 id 改成自定义
        Files.writeString(dir.resolve("providers.json"),
                "{\"entries\":[{\"id\":\"a\",\"provider\":\"lmstudio\"}],\"models_dev_ids\":true}");
        assertEquals("lmstudio", read("a").profile().provider());
    }

    @Test
    void liftingTwiceChangesNothingTheSecondTime() throws IOException {
        Files.writeString(dir.resolve("providers.json"),
                "{\"entries\":[{\"id\":\"a\",\"provider\":\"ollama\"},{\"id\":\"b\",\"provider\":\"kimi\"}]}");
        ProviderLibrary once = fresh();
        once.update(once.get("a"));   // 存一次:换成第 2 版落盘
        String saved = Files.readString(dir.resolve("providers.json"));

        ProviderLibrary again = fresh();
        again.update(again.get("a"));

        assertEquals(saved, Files.readString(dir.resolve("providers.json")), "第二次读写,文件一个字节不变");
        assertEquals("custom", again.get("a").profile().provider());
        assertEquals("moonshotai", again.get("b").profile().provider());
    }

    @Test
    void aSavedLibraryIsVersionTwoAndIsNeverLiftedAgain() throws IOException {
        ProviderLibrary.Entry made = fresh().create("x",
                new ConnectionProfile("lmstudio", null, "m", "", null, null, null, 0, null, 0, null));

        JsonObject saved = JsonParser.parseString(Files.readString(dir.resolve("providers.json"))).getAsJsonObject();
        assertEquals(2, saved.get("v").getAsInt());
        assertEquals("lmstudio", fresh().get(made.id()).profile().provider(), "新档案选的 lmstudio 不被当成老 id");
    }
}
