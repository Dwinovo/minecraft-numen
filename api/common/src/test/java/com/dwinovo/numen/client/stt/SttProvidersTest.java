package com.dwinovo.numen.client.stt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置的 provider 表。
 *
 * <p>这份数据是玩家在设置里唯一能看到的东西:填错一个 backend 名字,那家服务商就静默按
 * OpenAI 兼容去发;列一个不存在的模型 id,玩家选了只会收到 404,一点线索都没有。所以这里
 * 校验的是"表里每一项都能被代码认出来",不是校验网络。
 */
class SttProvidersTest {

    private static JsonArray providers() throws IOException {
        try (InputStream in = SttProviders.class.getResourceAsStream("/numen_stt.json")) {
            assertNotNull(in, "内置 numen_stt.json 得在 jar 里");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("providers");
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }

    @Test
    void everyBackendNameIsOneTheFactoryActuallyHandles() throws IOException {
        // 认不出来的 backend 会静默退回 whisper-http —— 对 WebSocket 那几家等于完全发错
        Set<String> known = Set.of(SttProviders.BACKEND_WHISPER_HTTP,
                SttProviders.BACKEND_DOUBAO, SttProviders.BACKEND_DASHSCOPE);
        for (var e : providers()) {
            String backend = str(e.getAsJsonObject(), "backend");
            assertTrue(known.contains(backend),
                    str(e.getAsJsonObject(), "id") + " 的 backend='" + backend + "' 没人认");
        }
    }

    @Test
    void idsAreUniqueBecauseTheOverlayMergesByThem() throws IOException {
        // 加载时用户文件按 id 覆盖内置;内置自己重了的话,覆盖谁就说不清了
        Set<String> seen = new HashSet<>();
        for (var e : providers()) {
            String id = str(e.getAsJsonObject(), "id");
            assertTrue(seen.add(id), "id 重了: " + id);
        }
    }

    @Test
    void everyProviderHasSomethingToShowAndSomewhereToSend() throws IOException {
        for (var e : providers()) {
            JsonObject p = e.getAsJsonObject();
            String id = str(p, "id");
            assertFalse(id.isBlank(), "provider 少了 id");
            assertFalse(str(p, "name").isBlank(), id + " 少了显示名");
            // custom 是留白让人自己填的,别的都该带上官方地址
            if (!"custom".equals(id)) {
                assertFalse(str(p, "baseUrl").isBlank(), id + " 少了 baseUrl");
            }
        }
    }

    @Test
    void realtimeProvidersPointAtWebSocketsAndBatchOnesAtHttp() throws IOException {
        for (var e : providers()) {
            JsonObject p = e.getAsJsonObject();
            String base = str(p, "baseUrl");
            if (base.isBlank()) {
                continue;
            }
            boolean realtime = !SttProviders.BACKEND_WHISPER_HTTP.equals(str(p, "backend"));
            assertEquals(realtime, base.startsWith("wss://"),
                    str(p, "id") + " 的协议和地址对不上: " + base);
        }
    }

    @Test
    void modelListsHaveNoBlanksOrDuplicates() throws IOException {
        for (var e : providers()) {
            JsonObject p = e.getAsJsonObject();
            if (!p.has("models")) {
                continue;
            }
            List<String> models = new ArrayList<>();
            for (var m : p.getAsJsonArray("models")) {
                String id = m.getAsString();
                assertFalse(id.isBlank(), str(p, "id") + " 里有个空模型 id");
                assertFalse(models.contains(id), str(p, "id") + " 里模型 id 重了: " + id);
                models.add(id);
            }
        }
    }

    @Test
    void doubaoOffersOnlyTheResourceIdsItsDocsList() throws IOException {
        // volc.bigasr.* 那两个撤了:新版控制台的 key 对它们是 403,列在这儿只会把人引进坑
        JsonObject doubao = null;
        for (var e : providers()) {
            if ("doubao".equals(str(e.getAsJsonObject(), "id"))) {
                doubao = e.getAsJsonObject();
            }
        }
        assertNotNull(doubao);
        List<String> models = new ArrayList<>();
        doubao.getAsJsonArray("models").forEach(m -> models.add(m.getAsString()));
        assertEquals(List.of("volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent"), models);
        assertEquals(DoubaoStt.DEFAULT_RESOURCE_ID, models.get(0),
                "下拉第一项就是代码里的缺省值,两边不能各说各话");
    }

    @Test
    void aFieldThatIsNotAModelSaysSoInsteadOfLying() throws IOException {
        // 豆包那一栏装的是资源档;它真正的 model_name 恒等于 bigmodel,没得选
        for (var e : providers()) {
            JsonObject p = e.getAsJsonObject();
            if ("doubao".equals(str(p, "id"))) {
                assertFalse(str(p, "modelLabel").isBlank(), "豆包那一栏不该顶着'模型'的名字");
            }
        }
    }

    @Test
    void theDefaultsInCodeMatchTheFirstEntryInTheTable() throws IOException {
        for (var e : providers()) {
            JsonObject p = e.getAsJsonObject();
            String id = str(p, "id");
            if (!"dashscope".equals(id)) {
                continue;
            }
            assertEquals(DashScopeStt.DEFAULT_MODEL, p.getAsJsonArray("models").get(0).getAsString());
            assertEquals(DashScopeStt.DEFAULT_URL, str(p, "baseUrl"));
        }
    }
}
