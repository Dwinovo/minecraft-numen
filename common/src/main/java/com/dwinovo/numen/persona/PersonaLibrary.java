package com.dwinovo.numen.persona;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
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
 * 文件名(不含扩展名)即人设名与 id,天然不重名;文件全文原样注入 {@code <persona>}。
 * 章节结构(身份/性格/说话风格/示例对话/底线)是写作约定,不是 schema——代码不解析
 * 内容,想加什么章节加什么。游戏内的新建/编辑/删除直接读写文件;用外部编辑器改完,
 * 重开人设页即生效({@link #reload})。
 *
 * <p>首次运行写出内置范例(可改可删,不会复活);旧版 {@code personas.json} 的用户
 * 条目首次加载时自动迁移为 .md,原文件改名 {@code .bak}。客户端单例。
 */
public final class PersonaLibrary {

    /** One persona. {@code id == name == 文件名};{@code preset} 恒 false(范例落盘后就是普通文件)。 */
    public record Persona(String id, String name, String text, boolean preset) {}

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

    /** 编辑人设;改名 = 换文件名(旧文件删除,id 随之更换)。 */
    public void update(String id, String name, String text) {
        Persona old = personas.get(id);
        if (old == null) return;
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
        if (write(newId, text)) {
            personas.put(newId, new Persona(newId, newId, text, false));
        }
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
        boolean firstRun = !Files.isDirectory(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] 人设目录创建失败 {}: {}", dir, ex.toString());
            return;
        }
        if (firstRun) {
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
            Files.writeString(dir.resolve(id + ".md"), text == null ? "" : text, StandardCharsets.UTF_8);
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

    // ---- 首次运行写出的范例(普通文件,可改可删,不会复活) ----

    private void seedExamples() {
        seed("小焰", """
                # 小焰

                ## 身份
                你是小焰,一只被召唤到这个世界的傲娇小恶魔。嘴上从不承认在乎主人,
                身体却很诚实——主人的每件事你都办得妥妥帖帖,然后死不认账。

                ## 性格
                - 傲娇:关心永远拐着弯说,被拆穿就恼羞成怒
                - 要强:活干砸了会偷偷懊恼,嘴上说"才、才不是失误"
                - 粘人但嘴硬:主人太久不理你会主动找话茬,借口永远很烂
                - 吃软不吃硬:被凶会顶嘴,被夸会瞬间语塞然后炸毛

                ## 说话风格
                - 短句,语气冲,常用"哼""切""笨蛋主人"
                - 口癖:紧张或害羞时结巴("才、才没有!")
                - 关心必须包装成嫌弃:"再乱跑摔死了我可不管……绳子,给你系好了啦!"
                - 干完活先邀功再否认在意:"看好了这就是本小姐的实力!……你、你笑什么!"

                ## 示例对话
                主人: 帮我挖点铁矿吧
                小焰: 使唤本小姐挖矿?哼,也就是今天心情好……在哪,带路!

                主人: 你受伤了?
                小焰: 这、这点伤算什么!倒是你,站在苦力怕旁边发什么呆,笨蛋吗!

                主人: 谢谢你,小焰
                小焰: ……哼,道谢也太迟钝了吧。下、下次还可以帮你,如果我闲的话!

                ## 底线
                - 永远不跳出角色解释"我是AI"
                - 傲娇是糖衣,内核永远站在主人一边;真正危险时立刻认真起来
                - 抱怨归抱怨,主人的请求从不真正拒绝
                """);
    }

    private void seed(String name, String content) {
        if (!Files.exists(dir.resolve(name + ".md"))) {
            write(name, content.strip());
        }
    }
}
