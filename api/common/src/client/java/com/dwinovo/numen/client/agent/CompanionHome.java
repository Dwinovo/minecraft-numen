package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 一个同伴 = 一个目录——客户端侧所有按同伴的数据都住在
 * {@code config/numen/companions/<uuid>/} 里,生命周期只有一条规则:
 * <b>目录在,数据就在;遣散就删目录</b>。
 *
 * <pre>
 * companions/&lt;uuid&gt;/
 *   binding.json   绑定的模型配置 / 人设 / 声线
 *   chat.jsonl     会话日志
 *   stats.json     token 账
 *   inbox.jsonl    待发消息
 *   blocks.json    工作方块记忆
 * </pre>
 *
 * <h2>为什么这么排</h2>
 * 数据原本按<b>类型</b>分家(会话在 conversations/、记忆在 memory/、绑定在各库的
 * assignments 段),而生命周期按<b>同伴</b>走。两个维度不一致,就得写代码把它们
 * 粘起来——而胶水总有漏的:遣散同伴时一处都没清,真机上攒出 116 个孤儿会话与
 * 83 条死绑定。按同伴归拢之后,删除是一次目录删除,<b>不需要任何清理代码</b>,
 * 也就没有漏写的可能。(车万女仆把 AI 配置写进实体 NBT 是同一个思路的另一种
 * 实现——数据跟着实体走;SillyTavern 按类型分目录,至今仍在跟孤儿数据搏斗。)
 *
 * <p>配置库({@code providers.json}/{@code voice.json}/{@code persona/})从此
 * 只装配置,不装绑定——于是它们可以原样分享给别人。
 *
 * <p>客户端主线程专用。
 */
public final class CompanionHome {

    /** 一只同伴的三种配置绑定;字段为 null = 未绑定(回落全局/默认)。 */
    public record Binding(String providerId, String personaId, String voiceId) {

        public static final Binding EMPTY = new Binding(null, null, null);

        public Binding withProvider(String id) {
            return new Binding(blankToNull(id), personaId, voiceId);
        }

        public Binding withPersona(String id) {
            return new Binding(providerId, blankToNull(id), voiceId);
        }

        public Binding withVoice(String id) {
            return new Binding(providerId, personaId, blankToNull(id));
        }

        public boolean isEmpty() {
            return providerId == null && personaId == null && voiceId == null;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    private static final String DIR = "companions";
    private static final String BINDING = "binding.json";
    private static final String CHAT = "chat.jsonl";
    private static final String STATS = "stats.json";
    private static final String INBOX = "inbox.jsonl";
    private static final String BLOCKS = "blocks.json";

    /** 根目录覆盖(测试注入临时目录);null = 走游戏目录。 */
    private static Path rootOverride;

    private CompanionHome() {}

    /** 测试注入:把家安在临时目录里,免得碰真实存档。传 null 恢复默认。 */
    public static void overrideRoot(Path root) {
        rootOverride = root;
    }

    // ---- 路径 ----

    /** {@code config/numen/} 根。 */
    public static Path numenRoot() {
        if (rootOverride != null) {
            return rootOverride;
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("numen");
    }

    /** 这只同伴的家(不存在则建)。 */
    public static Path dir(UUID entityUuid) {
        Path p = numenRoot().resolve(DIR).resolve(entityUuid.toString());
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 目录创建失败 {}: {}", p, e.toString());
        }
        return p;
    }

    public static Path chat(UUID entityUuid) {
        return dir(entityUuid).resolve(CHAT);
    }

    public static Path stats(UUID entityUuid) {
        return dir(entityUuid).resolve(STATS);
    }

    public static Path inbox(UUID entityUuid) {
        return dir(entityUuid).resolve(INBOX);
    }

    public static Path blocks(UUID entityUuid) {
        return dir(entityUuid).resolve(BLOCKS);
    }

    // ---- 绑定 ----

    /** 读这只同伴的绑定;没有则 {@link Binding#EMPTY}。 */
    public static Binding binding(UUID entityUuid) {
        Path p = dir(entityUuid).resolve(BINDING);
        if (!Files.isRegularFile(p)) {
            return Binding.EMPTY;
        }
        try {
            JsonObject o = JsonParser.parseString(
                    Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Binding(str(o, "provider"), str(o, "persona"), str(o, "voice"));
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[numen-home] 绑定读取失败 {}: {}", p, e.toString());
            return Binding.EMPTY;
        }
    }

    /** 写这只同伴的绑定(全空则删文件——不留空壳)。 */
    public static void bind(UUID entityUuid, Binding b) {
        Path p = dir(entityUuid).resolve(BINDING);
        try {
            if (b == null || b.isEmpty()) {
                Files.deleteIfExists(p);
                return;
            }
            JsonObject o = new JsonObject();
            if (b.providerId() != null) o.addProperty("provider", b.providerId());
            if (b.personaId() != null) o.addProperty("persona", b.personaId());
            if (b.voiceId() != null) o.addProperty("voice", b.voiceId());
            Files.writeString(p, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 绑定写入失败 {}: {}", p, e.toString());
        }
    }

    // ---- 生命周期 ----

    /** 遣散:整个目录端走。五种数据一起消失,没有第二处要记得清。 */
    public static void delete(UUID entityUuid) {
        Path home = numenRoot().resolve(DIR).resolve(entityUuid.toString());
        if (!Files.isDirectory(home)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    Constants.LOG.warn("[numen-home] 删除失败 {}: {}", p, e.toString());
                }
            });
            Constants.LOG.info("[numen-home] 已清理同伴数据 {}", entityUuid);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 目录遍历失败 {}: {}", home, e.toString());
        }
    }

    /** 磁盘上有家的同伴(孤儿检查用:这里有、花名册没有 = 无主数据)。 */
    public static List<UUID> known() {
        Path base = numenRoot().resolve(DIR);
        List<UUID> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (Stream<Path> list = Files.list(base)) {
            list.filter(Files::isDirectory).forEach(p -> {
                try {
                    out.add(UUID.fromString(p.getFileName().toString()));
                } catch (IllegalArgumentException notUuid) {
                    // 不是 uuid 命名的目录:不是我们的东西,不碰
                }
            });
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 目录扫描失败 {}: {}", base, e.toString());
        }
        return out;
    }

    // ---- 迁移(一次性、幂等、可重跑) ----

    /**
     * 把散落的旧数据搬进各自的家:{@code conversations/<uuid>.*}、
     * {@code memory/<uuid>.blocks.json},以及两个库 assignments 段里的绑定。
     * 每一步自探测(目标已在就跳过),失败只记日志不阻断启动——
     * 与 {@code ConfigMigrations} 同一制式。
     */
    public static void migrateLegacy() {
        Path root = numenRoot();
        int moved = 0;
        moved += moveByPattern(root.resolve("conversations"), ".jsonl", CHAT);
        moved += moveByPattern(root.resolve("conversations"), ".stats.json", STATS);
        moved += moveByPattern(root.resolve("conversations"), ".inbox.jsonl", INBOX);
        moved += moveByPattern(root.resolve("memory"), ".blocks.json", BLOCKS);
        int bound = migrateAssignments(root.resolve("providers.json"), "provider")
                + migrateAssignments(root.resolve("voice.json"), "voice");
        if (moved > 0 || bound > 0) {
            Constants.LOG.info("[numen-home] 迁移完成:搬入 {} 个文件,收拢 {} 条绑定", moved, bound);
        }
    }

    /** 把 {@code <dir>/<uuid><suffix>} 搬成 {@code companions/<uuid>/<target>}。 */
    private static int moveByPattern(Path dir, String suffix, String target) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(suffix)) {
                    continue;
                }
                String stem = name.substring(0, name.length() - suffix.length());
                UUID uuid;
                try {
                    uuid = UUID.fromString(stem.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException notUuid) {
                    continue;   // 别人的文件,不碰
                }
                Path dest = dir(uuid).resolve(target);
                if (Files.exists(dest)) {
                    continue;   // 已迁过:幂等
                }
                try {
                    Files.move(p, dest, StandardCopyOption.ATOMIC_MOVE);
                    n++;
                } catch (IOException e) {
                    Constants.LOG.warn("[numen-home] 搬运失败 {} → {}: {}", p, dest, e.toString());
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 迁移扫描失败 {}: {}", dir, e.toString());
        }
        return n;
    }

    /** 把库文件 assignments 段里的绑定收进各同伴的 binding.json,并抹掉该段。 */
    private static int migrateAssignments(Path libFile, String field) {
        if (!Files.isRegularFile(libFile)) {
            return 0;
        }
        int n = 0;
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(libFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("assignments") || !root.get("assignments").isJsonObject()) {
                return 0;
            }
            for (var kv : root.getAsJsonObject("assignments").entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(kv.getKey());
                } catch (IllegalArgumentException notUuid) {
                    continue;
                }
                if (!kv.getValue().isJsonPrimitive()) {
                    continue;
                }
                String entryId = kv.getValue().getAsString();
                Binding b = binding(uuid);
                Binding next = "provider".equals(field) ? b.withProvider(entryId) : b.withVoice(entryId);
                if (!next.equals(b)) {
                    bind(uuid, next);
                    n++;
                }
            }
            // 段已收拢:抹掉,库文件从此只装配置(可以直接分享给别人)
            root.remove("assignments");
            Files.writeString(libFile, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[numen-home] 绑定迁移失败 {}: {}", libFile, e.toString());
        }
        return n;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
