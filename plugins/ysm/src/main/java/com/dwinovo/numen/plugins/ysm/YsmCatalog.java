package com.dwinovo.numen.plugins.ysm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 玩家装了哪些模型、每个模型有哪些贴图和动作——读 YSM 的模型目录得来,不问 YSM 要。
 *
 * <h2>目录规则(真机确认)</h2>
 * <pre>
 * config/yes_steve_model/
 *   builtin/default/ysm.json            → 模型 id "default"
 *   builtin/misc/ysm-pack.json          → 这是个包,不是模型
 *   builtin/misc/1_alex/ysm.json        → 模型 id "misc/1_alex"
 *   builtin/misc/1_alex/animations/*.json
 *   custom/…                            → 玩家自己放的,同样的形状
 * </pre>
 * 判据只有一条:<b>带 ysm.json 的目录就是一个模型</b>,id 是它相对 builtin/custom 的路径。
 * 老一点的 YSM(2.4.x)把内置模型也放在 custom/ 下、id 不分层(wine_fox_jk),同一条判据照样成立。
 *
 * <p>贴图 id 是 {@code ysm.json} 里 {@code files.player.texture} 列出的文件名去掉扩展名
 * ({@code textures/skin.png} → {@code skin}),第一个是模型的默认贴图。玩家存档里
 * {@code select_texture} 存的就是这个形式,2.4.1 与 2.6.5 都一样。列表里的条目有两种写法:
 * 直接一个路径字符串,或者一个对象、底色贴图在 {@code uv} 键下(同一个内置包里
 * {@code wine_fox/14_momo} 就是后者),两种都要认。
 *
 * <p>动作名是各 {@code animations/*.animation.json} 里 {@code animations} 对象的键。
 * 不同模型自带的不一样——{@code default} 有 50 个,{@code misc/1_alex} 有 45 个。
 */
public final class YsmCatalog {

    private static final String MODEL_MARKER = "ysm.json";
    private static final String[] ROOTS = {"builtin", "custom"};

    private final Path base;

    /** @param configDir 加载器的 {@code config/} 根,由宿主给({@link YsmHost#configDir()}) */
    public YsmCatalog(Path configDir) {
        this.base = configDir.resolve("yes_steve_model");
    }

    /** 这台机器上装了的全部模型 id。YSM 没装或目录不在时返回空表。 */
    public List<String> models() {
        List<String> out = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = base.resolve(root);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().equals(MODEL_MARKER))
                    .map(p -> dir.relativize(p.getParent()).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(id -> !id.isEmpty())
                    .forEach(out::add);
            } catch (IOException ignored) {
                // 读不到就当没有:目录缺失不该让工具调用失败,上层会说"没找到模型"
            }
        }
        return out;
    }

    /** 本机目录里有没有这个模型。 */
    public boolean hasModel(String modelId) {
        return modelDir(modelId) != null;
    }

    /**
     * 某个模型自带的贴图 id,按 ysm.json 里的顺序,第一个是默认贴图。
     * 模型不在本机目录里、或 ysm.json 里没列贴图时返回空表——两种情况调用方用 {@link #hasModel} 分。
     */
    public List<String> textures(String modelId) {
        Path dir = modelDir(modelId);
        if (dir == null) return List.of();
        List<String> out = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(dir.resolve(MODEL_MARKER))) {
            JsonObject spec = JsonParser.parseReader(r).getAsJsonObject();
            JsonElement player = spec.has("files") ? spec.getAsJsonObject("files").get("player") : null;
            JsonElement textures = player != null && player.isJsonObject() ? player.getAsJsonObject().get("texture") : null;
            if (textures != null && textures.isJsonArray()) {
                for (JsonElement t : textures.getAsJsonArray()) {
                    String path = texturePath(t);
                    if (path != null) out.add(textureId(path));
                }
            }
        } catch (Exception ignored) {
            // ysm.json 坏了或形状不对:当作没列贴图,调用方会说清楚
        }
        return out;
    }

    /** 列表条目 → 底色贴图路径:字符串原样,对象取 {@code uv};别的形状不认。 */
    private static String texturePath(JsonElement entry) {
        if (entry.isJsonPrimitive()) return entry.getAsString();
        if (entry.isJsonObject() && entry.getAsJsonObject().has("uv")) {
            return entry.getAsJsonObject().get("uv").getAsString();
        }
        return null;
    }

    /** 某个模型自带的动作名。传 null 或找不到该模型时返回空集。 */
    public Set<String> emotesFor(Ysm.Look look) {
        if (look == null || look.model() == null || look.model().isBlank()) return Set.of();
        Path dir = modelDir(look.model());
        if (dir == null) return Set.of();
        Path anim = dir.resolve("animations");
        if (!Files.isDirectory(anim)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(anim)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try (Reader r = Files.newBufferedReader(p)) {
                    JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                    if (root.has("animations")) {
                        out.addAll(root.getAsJsonObject("animations").keySet());
                    }
                } catch (Exception ignored) {
                    // 单个动画文件坏了不该让整份清单没了
                }
            });
        } catch (IOException ignored) {
            return Set.of();
        }
        return out;
    }

    /** 模型所在目录(带 ysm.json 的那个);本机没有这个模型时为 null。 */
    private Path modelDir(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        for (String root : ROOTS) {
            Path dir = base.resolve(root).resolve(modelId);
            if (Files.isRegularFile(dir.resolve(MODEL_MARKER))) return dir;
        }
        return null;
    }

    /** {@code textures/skin.png} → {@code skin}。 */
    private static String textureId(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
