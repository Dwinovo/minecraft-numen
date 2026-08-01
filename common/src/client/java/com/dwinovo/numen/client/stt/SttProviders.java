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
import java.util.List;

/**
 * 语音输入 provider 注册表(TouhouLittleMaid 式思路,numen 原生实现,镜像
 * {@code ModelRegistry}):每个 provider 一个 OpenAI 兼容 base URL + 一组已知
 * transcription 模型 id;{@code backend} 选实现(目前只有 {@code whisper-http}
 * 批量,未来加流式只加实现+一条数据)。UI 下拉与 {@link #fromConfig} 工厂共用。
 *
 * <p>USER-EDITABLE:首次加载把内置 {@code /numen_stt.json} 拷到
 * {@code config/numen/stt.json},之后以用户文件为准(编辑它加自己的 provider/模型);
 * 用户文件坏了回退内置默认。加一家 = 改数据不改代码,和 LLM 的 models.json 同构。
 */
public final class SttProviders {

    public static final String BACKEND_WHISPER_HTTP = "whisper-http";

    public record Option(String id, String displayName, String backend,
                         String defaultBaseUrl, List<String> models) {
        /** First known model, or empty for a custom (free-text-only) provider. */
        public String defaultModel() {
            return models.isEmpty() ? "" : models.get(0);
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
                ? new Option("custom", "Custom (OpenAI-compatible)", BACKEND_WHISPER_HTTP, "", List.of())
                : PROVIDERS.get(0);
    }

    /**
     * 据全局配置实例化后端。无 API key 时返回 {@code null}(=语音输入未启用)。
     * baseUrl / model 留空时回落到所选预设的默认值。
     */
    public static SttBackend fromConfig(INumenConfig cfg) {
        String key = cfg.getSttApiKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        Option opt = byId(cfg.getSttProvider());
        String base = cfg.getSttBaseUrl();
        if (base == null || base.isBlank()) {
            base = opt.defaultBaseUrl();
        }
        String model = cfg.getSttModel();
        if (model == null || model.isBlank()) {
            model = opt.defaultModel();
        }
        return switch (opt.backend()) {
            case BACKEND_WHISPER_HTTP -> new WhisperHttpStt(base, key, model);
            default -> new WhisperHttpStt(base, key, model);
        };
    }

    // ---- loading (mirror of ModelRegistry) ----

    private static List<Option> load() {
        String bundled = readBundled();
        String json = bundled;
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("stt.json");
            if (Files.exists(file)) {
                json = Files.readString(file, StandardCharsets.UTF_8);   // user-authoritative
            } else if (bundled != null) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, bundled, StandardCharsets.UTF_8);  // seed for editing
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen] couldn't read/seed config/numen/stt.json, using bundled", e);
        }
        List<Option> out = parse(json);
        if (out.isEmpty() && bundled != null && !bundled.equals(json)) {
            Constants.LOG.warn("[numen] user stt.json yielded no providers, falling back to bundled");
            out = parse(bundled);
        }
        return List.copyOf(out);
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
                        List.copyOf(models)));
            }
        } catch (Exception e) {
            Constants.LOG.error("[numen] failed to parse stt.json", e);
        }
        return out;
    }
}
