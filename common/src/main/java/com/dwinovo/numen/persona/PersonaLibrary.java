package com.dwinovo.numen.persona;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 玩家的人设库:{@code config/numen/persona/} 目录,<b>一个 .md 文件就是一个人设</b>。
 * 文件名即人设名与 id(天然不重名),文件内容想写啥写啥——全文原样注入
 * {@code <persona>},零结构约束,UI 列表用正文截断做预览。
 *
 * <p>内置示例存在 jar 资源里({@code assets/numen_api/persona/}),目录中的
 * {@code .init} 哨兵文件缺失时(首次运行/被手动删除)从 jar 复制出缺失的示例并
 * 重写哨兵——已存在的同名文件不覆盖,用户的修改不会被吃掉。
 *
 * <p>旧版 {@code personas.json} 的用户条目首次加载时自动迁移为 .md
 * (原文件改名 .bak)。客户端单例。
 */
public final class PersonaLibrary {

    /** One persona. {@code id == name == 文件名};{@code preset} 恒 false(示例落盘后就是普通文件)。 */
    public record Persona(String id, String name, String text, boolean preset) {}

    /** 哨兵文件:删掉它,下次启动重新从 jar 复制缺失的内置示例。 */
    private static final String INIT_MARKER = ".init";
    /** jar 里的内置示例(资源路径 assets/numen_api/persona/ 下的文件名)。
     *  原型覆盖:傲娇(小焰)/猫娘(团子)/温柔守护者(暖暖)/元气伙伴(阿光)/
     *  沉稳老哥(老墨)——低龄玩家也有零门槛的选择。 */
    private static final String[] EXAMPLES = {"小焰.md", "团子.md", "暖暖.md", "阿光.md", "老墨.md"};

    private static PersonaLibrary instance;

    private final Path dir;
    private final Path legacyJson;
    private final Map<String, Persona> personas = new LinkedHashMap<>();

    private PersonaLibrary(Path dir, Path legacyJson) {
        this.dir = dir;
        this.legacyJson = legacyJson;
    }

    public static PersonaLibrary instance() {
        if (instance == null) {
            Path cfg = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen");
            instance = new PersonaLibrary(cfg.resolve("persona"), cfg.resolve("personas.json"));
            instance.load();
        }
        return instance;
    }

    /** 重扫目录——打开人设页/召唤面板时调用,外部编辑器的修改即时可见。 */
    public void reload() {
        load();
    }

    /** All personas, 文件名序。 */
    public List<Persona> list() {
        return new ArrayList<>(personas.values());
    }

    public Persona get(String id) {
        return id == null ? null : personas.get(id);
    }

    /** 新建人设 = 写一个 .md。重名自动加 _2 后缀(文件名即身份)。 */
    public Persona create(String name, String text) {
        String id = uniqueName(sanitizeName(name));
        if (!write(id, text)) return null;
        Persona p = new Persona(id, id, text, false);
        personas.put(id, p);
        return p;
    }

    /** 编辑人设;改名 = 换文件名(旧文件删除,id 随之更换)。返回落盘后的条目。 */
    public Persona update(String id, String name, String text) {
        Persona old = personas.get(id);
        if (old == null) return null;
        String newId = sanitizeName(name);
        if (!newId.equals(id)) {
            newId = uniqueName(newId);
            try {
                Files.deleteIfExists(dir.resolve(id + ".md"));
            } catch (IOException ex) {
                Constants.LOG.warn("[numen-persona] 旧人设文件删除失败 {}: {}", id, ex.toString());
            }
            personas.remove(id);
        }
        if (!write(newId, text)) return null;
        Persona p = new Persona(newId, newId, text, false);
        personas.put(newId, p);
        return p;
    }

    /** 删除人设文件。 */
    public void remove(String id) {
        if (personas.remove(id) == null) return;
        try {
            Files.deleteIfExists(dir.resolve(id + ".md"));
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] 人设文件删除失败 {}: {}", id, ex.toString());
        }
    }

    /** 复制一份可编辑副本。 */
    public Persona clonePersona(String id) {
        Persona src = personas.get(id);
        if (src == null) return null;
        return create(src.name() + " 副本", src.text());
    }

    // ---- pending summon assignment ----
    // The persona picked at summon time, keyed by the companion name — the client doesn't know the new
    // companion's UUID until the roster snapshot arrives, so it's resolved there (CompanionListPayload).

    private static final Map<String, String> PENDING_SUMMON = new LinkedHashMap<>();

    /** Remember the persona chosen for a companion being summoned (by name). {@code personaId} null = default. */
    public static void pendSummon(String name, String personaId) {
        if (name == null || personaId == null) return;
        PENDING_SUMMON.put(name, personaId);
    }

    /** Take (and clear) the persona pending for a just-arrived companion name, or null if none/unknown. */
    public static Persona takePendingSummon(String name) {
        String id = PENDING_SUMMON.remove(name);
        return id == null ? null : instance().get(id);
    }

    // ---- persistence ----

    private void load() {
        personas.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] 人设目录创建失败 {}: {}", dir, ex.toString());
            return;
        }
        if (!Files.exists(dir.resolve(INIT_MARKER))) {
            seedExamples();
        }
        migrateLegacyJson();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String stem = p.getFileName().toString();
                        stem = stem.substring(0, stem.length() - 3);
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8).strip();
                            if (!text.isEmpty()) {
                                personas.put(stem, new Persona(stem, stem, text, false));
                            }
                        } catch (IOException ex) {
                            Constants.LOG.warn("[numen-persona] 人设读取失败 {}: {}", p, ex.toString());
                        }
                    });
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] 人设目录扫描失败: {}", ex.toString());
        }
    }

    /** 旧版 personas.json 的用户条目一次性迁移为 .md,原文件改名 .bak。 */
    private void migrateLegacyJson() {
        if (!Files.isRegularFile(legacyJson)) return;
        int migrated = 0;
        try {
            JsonObject o = JsonParser.parseString(
                    Files.readString(legacyJson, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("personas") && o.get("personas").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("personas")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject po = el.getAsJsonObject();
                    if (po.has("preset") && po.get("preset").getAsBoolean()) continue;
                    String name = str(po, "name");
                    String text = str(po, "text");
                    if (text.isBlank()) continue;
                    String id = uniqueName(sanitizeName(name.isBlank() ? str(po, "id") : name));
                    if (write(id, text)) migrated++;
                }
            }
            Files.move(legacyJson, legacyJson.resolveSibling("personas.json.bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.info("[numen-persona] personas.json 已迁移 {} 条用户人设为 .md", migrated);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-persona] personas.json 迁移失败(保留原文件): {}", ex.toString());
        }
    }

    private boolean write(String id, String text) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(id + ".md"),
                    (text == null ? "" : text.strip()) + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] 人设写盘失败 {}: {}", id, ex.toString());
            return false;
        }
    }

    /** 文件名合法化:去掉 Windows 非法字符与首尾空白;空名回落 "persona"。 */
    private static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.strip().replaceAll("[\\\\/:*?\"<>|]", "");
        return s.isEmpty() ? "persona" : s;
    }

    /** 已存在同名文件时追加 _2/_3…(文件名即身份,不覆盖别人)。 */
    private String uniqueName(String base) {
        String cand = base;
        int i = 2;
        while (Files.exists(dir.resolve(cand + ".md"))) {
            cand = base + "_" + i++;
        }
        return cand;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    // ---- 内置示例:从 jar 资源复制(.init 哨兵缺失时) ----

    private void seedExamples() {
        for (String res : EXAMPLES) {
            Path target = dir.resolve(res);
            if (Files.exists(target)) continue;   // 用户改过的/已有的不覆盖
            try (InputStream in = PersonaLibrary.class.getResourceAsStream(
                    "/assets/numen_api/persona/" + res)) {
                if (in == null) {
                    Constants.LOG.warn("[numen-persona] jar 内示例缺失: {}", res);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException ex) {
                Constants.LOG.warn("[numen-persona] 示例复制失败 {}: {}", res, ex.toString());
            }
        }
        try {
            Files.writeString(dir.resolve(INIT_MARKER),
                    "删除此文件后,下次启动会从模组内恢复内置示例人设(不覆盖已存在的同名文件)。\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] .init 哨兵写入失败: {}", ex.toString());
        }
    }
}
