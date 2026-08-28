package com.dwinovo.numen.plugins.ysm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

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
 * 玩家装了哪些模型、每个模型有哪些动作——读 YSM 的模型目录得来,不问 YSM 要。
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
 *
 * <p>动作名是各 {@code animations/*.animation.json} 里 {@code animations} 对象的键。
 * 不同模型自带的不一样——{@code default} 有 50 个,{@code misc/1_alex} 有 45 个。
 */
public final class YsmCatalog {

    private static final String MODEL_MARKER = "ysm.json";
    private static final String[] ROOTS = {"builtin", "custom"};

    private YsmCatalog() {}

    private static Path base() {
        return FMLPaths.CONFIGDIR.get().resolve("yes_steve_model");
    }

    /** 这台机器上装了的全部模型 id。YSM 没装或目录不在时返回空表。 */
    public static List<String> models() {
        List<String> out = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = base().resolve(root);
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

    /** 某个模型自带的动作名。传 null 或找不到该模型时返回空集。 */
    public static Set<String> emotesFor(Ysm.Look look) {
        if (look == null || look.model() == null || look.model().isBlank()) return Set.of();
        for (String root : ROOTS) {
            Path anim = base().resolve(root).resolve(look.model()).resolve("animations");
            if (!Files.isDirectory(anim)) continue;
            Set<String> out = new LinkedHashSet<>();
            try (Stream<Path> files = Files.list(anim)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    try (Reader r = Files.newBufferedReader(p)) {
                        JsonObject root2 = JsonParser.parseReader(r).getAsJsonObject();
                        if (root2.has("animations")) {
                            out.addAll(root2.getAsJsonObject("animations").keySet());
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
        return Set.of();
    }
}
