package com.dwinovo.numen.plugins.tlm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 谁穿哪套女仆模型——这个问题的<b>唯一</b>答案。
 *
 * <h2>为什么住在客户端</h2>
 * 女仆模型包是玩家自己丢进 {@code touhou_little_maid/} 的资源,只有客户端知道
 * 装了哪些({@code CustomPackLoader} 本身就是客户端类)。服务端连模型 id 的合法性
 * 都校验不了,把选择放上去只会得到一份自己也不认识的数据。所以"穿什么"就是
 * 一件主人这边的事,跟主人装了哪些包同源。
 *
 * <p>代价说明白:多人游戏里别的玩家看不到你给同伴挑的模型——除非他也装了同一个包
 * 并自己挑。这是模型包分发方式决定的,不是这里偷懒。
 *
 * <h2>为什么写在 config/numen/ 而不是自己开一个目录</h2>
 * 路径从 {@code NumenApi.configDir()} 拿。Numen 的东西都在这一个目录下,玩家找
 * 一次就够——引擎自己刚在 #66 里把根收拢过,插件别又散出去。
 */
public final class Wardrobe {

    private static final Logger LOG = LoggerFactory.getLogger("numen-tlm");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, String> WORN = new LinkedHashMap<>();

    private Wardrobe() {}

    /** 这只同伴穿的模型 id;没挑过返回 null(那就照常渲染成玩家)。 */
    public static String worn(UUID companion) {
        return WORN.get(companion);
    }

    /** 有没有人穿着女仆模型——渲染钩子每帧问一次,空表时直接短路。 */
    public static boolean empty() {
        return WORN.isEmpty();
    }

    /** 换一套;{@code modelId} 传 null 表示脱下,恢复原来的玩家外观。 */
    public static void wear(UUID companion, String modelId) {
        if (modelId == null) {
            WORN.remove(companion);
        } else {
            WORN.put(companion, modelId);
        }
        save();
    }

    /** 引擎给的目录,插件不自己拼——见 {@code NumenApi.configDir()}。 */
    private static Path dir;

    static void bind(Path configDir) {
        dir = configDir;
    }

    private static Path file() {
        return dir.resolve("numen_tlm-wardrobe.json");
    }

    public static void load() {
        if (dir == null) return;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, String> raw = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
            if (raw == null) return;
            WORN.clear();
            raw.forEach((k, v) -> {
                try {
                    WORN.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException ignored) {
                    // 手改坏的一行,跳过就是了,不能让整份配置一起作废
                }
            });
        } catch (Exception e) {
            LOG.warn("读不了衣柜,先按空的来: {}", e.toString());
        }
    }

    private static void save() {
        if (dir == null) return;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Map<String, String> raw = new LinkedHashMap<>();
            WORN.forEach((k, v) -> raw.put(k.toString(), v));
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(raw, w);
            }
        } catch (Exception e) {
            LOG.warn("衣柜存盘失败: {}", e.toString());
        }
    }
}
