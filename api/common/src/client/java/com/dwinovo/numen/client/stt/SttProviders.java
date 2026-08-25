package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音输入 provider 注册表:每个 provider 一个 base URL + 一组已知模型 id;{@code backend}
 * 选实现——{@code whisper-http} 是批量上传,{@code doubao} 是流式 WebSocket。UI 下拉与
 * {@link #fromConfig} 工厂共用。加一家 OpenAI 兼容的 = 改数据不改代码,和 LLM 的
 * models.json 同构。
 *
 * <p>USER-EDITABLE:首次加载把内置 {@code /numen_stt.json} 拷到
 * {@code config/numen/stt.json} 供编辑。加载时<b>内置打底、用户文件按 id 覆盖</b>:用户改过的
 * 条目照他的算,同时我们以后新加的 provider 不会被早就落盘的那份快照挡在外面。
 */
public final class SttProviders {

    public static final String BACKEND_WHISPER_HTTP = "whisper-http";
    /** 豆包语音(火山引擎)大模型实时识别:流式 WebSocket,不是 OpenAI 兼容 REST。 */
    public static final String BACKEND_DOUBAO = "doubao";
    /** 阿里云百炼(DashScope)实时识别:流式 WebSocket,run-task 指令协议。 */
    public static final String BACKEND_DASHSCOPE = "dashscope";
    /** 阶跃星辰(StepFun)识别:私有 JSON+SSE,批量上传、结果流式先到。 */
    public static final String BACKEND_STEPFUN = "stepfun-asr";

    /**
     * @param modelLabel 那一栏在设置里叫什么。多数服务商就是"模型",但也有名不副实的——豆包那一栏
     *                   装的是资源档(计费方式),它真正的模型名恒等于 {@code bigmodel},没得选。
     *                   留空即用默认的"模型"。
     */
    public record Option(String id, String displayName, String backend,
                         String defaultBaseUrl, List<String> models, String modelLabel) {
        /** First known model, or empty for a custom (free-text-only) provider. */
        public String defaultModel() {
            return models.isEmpty() ? "" : models.get(0);
        }

        public boolean hasModelLabel() {
            return modelLabel != null && !modelLabel.isBlank();
        }
    }

    private static volatile List<Option> PROVIDERS = load();

    private SttProviders() {}

    /** Re-read the user file (after an edit). */
    public static void reload() {
        PROVIDERS = load();
    }

    public static List<Option> all() {
        return PROVIDERS;
    }

    public static Option byId(String id) {
        String norm = id == null ? "" : id.strip().toLowerCase();
        for (Option o : PROVIDERS) {
            if (o.id().equals(norm)) {
                return o;
            }
        }
        return PROVIDERS.isEmpty()
                ? new Option("custom", "Custom (OpenAI-compatible)", BACKEND_WHISPER_HTTP, "", List.of(), "")
                : PROVIDERS.get(0);
    }

    /**
     * 据全局配置实例化后端。无 API key 时返回 {@code null}(=语音输入未启用)。
     * baseUrl / model 留空时回落到所选预设的默认值。
     */
    public static SttBackend fromConfig(INumenConfig cfg) {
        Option opt = byId(cfg.getSttProvider());
        String key = cfg.getSttApiKey() == null ? "" : cfg.getSttApiKey();
        // 密钥必填与否由后端定:云端流式(doubao/dashscope)缺钥握手必败;
        // whisper-http 兼容端点常见自建无鉴权,空钥放行(请求侧空钥不带 Authorization)。
        if (key.isBlank() && !BACKEND_WHISPER_HTTP.equals(opt.backend())) {
            Constants.LOG.warn("[numen-stt] provider '{}' 没填 API Key,语音输入没开", opt.id());
            return null;
        }
        String base = cfg.getSttBaseUrl();
        if (base == null || base.isBlank()) {
            base = opt.defaultBaseUrl();
        }
        String model = cfg.getSttModel();
        if (model == null || model.isBlank()) {
            model = opt.defaultModel();
        }
        SttBackend backend = build(opt, base, key, model);
        // 每次开录都留一行:出问题时这行说明当时用的到底是哪一家、哪个模型/资源档
        Constants.LOG.info("[numen-stt] {}", backend.describe());
        return backend;
    }

    private static SttBackend build(Option opt, String base, String key, String model) {
        return switch (opt.backend()) {
            case BACKEND_WHISPER_HTTP -> new WhisperHttpStt(base, key, model);
            case BACKEND_DOUBAO -> new DoubaoStt(base, key, model);
            case BACKEND_DASHSCOPE -> new DashScopeStt(base, key, model);
            case BACKEND_STEPFUN -> new StepFunStt(base, key, model);
            default -> {
                Constants.LOG.warn("[numen-stt] provider '{}' 的 backend='{}' 不认识,按 {} 处理",
                        opt.id(), opt.backend(), BACKEND_WHISPER_HTTP);
                yield new WhisperHttpStt(base, key, model);
            }
        };
    }

    // ---- loading ----

    /**
     * 内置打底,用户文件按 id 覆盖,用户自定义的追加在后。
     *
     * <p>不是"有用户文件就只认用户文件"——那份文件是他第一次进游戏那天的快照,里面不会有我们
     * 后来加的 provider,于是新服务商对所有老玩家永远不出现,而且不报错。
     */
    private static List<Option> load() {
        String bundled = readBundled();
        Map<String, Option> byId = new LinkedHashMap<>();
        for (Option o : parse(bundled)) {
            byId.put(o.id(), o);
        }
        for (Option o : parse(readOrSeedUserFile(bundled))) {
            byId.put(o.id(), o);
        }
        return List.copyOf(byId.values());
    }

    /** 读 {@code config/numen/stt.json};没有就先拿内置的播一份下去供编辑。 */
    private static String readOrSeedUserFile(String bundled) {
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("stt.json");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            if (bundled != null) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, bundled, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen] couldn't read/seed config/numen/stt.json", e);
        }
        return null;
    }

    private static String readBundled() {
        try (var in = SttProviders.class.getResourceAsStream("/numen_stt.json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Constants.LOG.error("[numen] numen_stt.json not readable", e);
            return null;
        }
    }

    private static List<Option> parse(String json) {
        List<Option> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (var pe : root.getAsJsonArray("providers")) {
                JsonObject p = pe.getAsJsonObject();
                List<String> models = new ArrayList<>();
                if (p.has("models")) {
                    for (var me : p.getAsJsonArray("models")) {
                        models.add(me.getAsString());
                    }
                }
                out.add(new Option(
                        p.get("id").getAsString(),
                        p.has("name") ? p.get("name").getAsString() : p.get("id").getAsString(),
                        p.has("backend") ? p.get("backend").getAsString() : BACKEND_WHISPER_HTTP,
                        p.has("baseUrl") ? p.get("baseUrl").getAsString() : "",
                        List.copyOf(models),
                        p.has("modelLabel") ? p.get("modelLabel").getAsString() : ""));
            }
        } catch (Exception e) {
            Constants.LOG.error("[numen] failed to parse stt.json", e);
        }
        return out;
    }
}
