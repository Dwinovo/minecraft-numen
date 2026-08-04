package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRoster;
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
 *   world          她属于哪个存档(对账用,见 {@link #reconcile})
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
    private static final String WORLD = "world";

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

    /** 这只同伴家的位置——<b>只算路径,不建目录</b>。 */
    private static Path at(UUID entityUuid) {
        return numenRoot().resolve(DIR).resolve(entityUuid.toString());
    }

    /**
     * 这只同伴的家,不存在则建——<b>只给要往里写东西的人用</b>。
     *
     * <p>读取一律走 {@link #at}:让"读"能建目录,就等于让 {@code binding(uuid)} 这种
     * 纯查询把刚删掉的家复活成一个空壳。而空壳没有 {@code world} 标记,
     * {@link #reconcile} 又永远不碰无标记的目录——于是它会一直烂在那儿。
     */
    public static Path dir(UUID entityUuid) {
        Path p = at(entityUuid);
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
        Path p = at(entityUuid).resolve(BINDING);
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
        // 解绑走 at():删一个文件不该顺手把家建出来
        boolean unbind = b == null || b.isEmpty();
        Path p = (unbind ? at(entityUuid) : dir(entityUuid)).resolve(BINDING);
        try {
            if (unbind) {
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

    // ---- 世界归属 ----

    /**
     * 这只同伴属于哪个世界(存档/服务器),没标过则 null。
     *
     * <p>为什么要标:{@code companions/} 是<b>跨存档共用</b>的目录,而"她不在名册上"
     * 这句话只在同一个世界内才等于"被遣散了"。不标世界的话,换个存档进去,上一个
     * 存档的同伴全都不在新名册上——照着删就是灭顶之灾。
     */
    public static String world(UUID entityUuid) {
        Path p = at(entityUuid).resolve(WORLD);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 世界标记读取失败 {}: {}", p, e.toString());
            return null;
        }
    }

    /** 认领:把这只同伴记在这个世界名下(已经是了就不重写)。 */
    public static void claim(UUID entityUuid, String worldId) {
        if (worldId == null || worldId.isBlank() || worldId.equals(world(entityUuid))) {
            return;
        }
        try {
            Files.writeString(dir(entityUuid).resolve(WORLD), worldId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 世界标记写入失败 {}: {}", entityUuid, e.toString());
        }
    }

    /**
     * 对账:名册说这个世界还剩这些同伴,把<b>本世界</b>其余的家全删掉,同时给名册上的
     * 认领世界归属。返回删掉了几只。
     *
     * <p>这是删除本地数据的<b>唯一</b>入口,而且是<b>状态同步</b>不是事件通知——
     * 掉线期间遣散的、信号丢了的、上个版本留下的孤儿,只要在同一个世界,下次收到名册
     * 就一次对平。没标世界的旧数据一个都不碰(见 {@link CompanionRoster#orphans})。
     */
    public static int reconcile(String worldId, java.util.Set<UUID> onRoster) {
        if (worldId == null || worldId.isBlank()) {
            return 0;
        }
        for (UUID uuid : onRoster) {
            claim(uuid, worldId);
        }
        java.util.Map<UUID, String> homeWorlds = new java.util.LinkedHashMap<>();
        for (UUID uuid : known()) {
            homeWorlds.put(uuid, world(uuid));
        }
        List<UUID> gone = CompanionRoster.orphans(homeWorlds, worldId, onRoster);
        for (UUID uuid : gone) {
            delete(uuid);
        }
        reportUnclaimed(homeWorlds);
        return gone.size();
    }

    /** 每次进游戏提醒一次:有多少来历不明的旧数据。自动删不了(不知道是谁的),
     *  但也不能装作没有——它们正是这套东西要根治的那个问题。 */
    private static void reportUnclaimed(java.util.Map<UUID, String> homeWorlds) {
        if (unclaimedReported) {
            return;
        }
        unclaimedReported = true;
        long n = homeWorlds.values().stream().filter(java.util.Objects::isNull).count();
        if (n > 0) {
            Constants.LOG.info("[numen-home] {} 份同伴数据没有世界归属(旧版本迁移来的),"
                    + "不会被自动清理;她们所属的存档登录一次即可认领", n);
        }
    }

    /** 见 {@link #reportUnclaimed};断开连接时复位,换存档会重新数一次。 */
    private static boolean unclaimedReported;

    /** 断开连接:下次进游戏重新提醒一次无主数据。 */
    public static void onDisconnect() {
        unclaimedReported = false;
    }

    // ---- 生命周期 ----

    /**
     * 遣散:整个目录端走。六种数据一起消失,没有第二处要记得清。
     *
     * <p>先掐大脑再删盘:她要是正有一个回合在飞,响应落地时会照常往会话日志写,
     * 而写日志会把父目录建回来——刚删干净的家又长出一个只剩半截对话的空壳。
     * 这条顺序放在这里而不是交给调用方记,是因为忘记的代价是脏数据。
     */
    public static void delete(UUID entityUuid) {
        AgentLoopRegistry.dispose(entityUuid);
        Path home = at(entityUuid);
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
     * 把散落的旧数据搬进各自的家:两个库 assignments 段里的绑定、
     * {@code conversations/<uuid>.*}、{@code memory/<uuid>.blocks.json},
     * 以及记在会话日志事件里的人设绑定。每一步自探测(目标已在就跳过),
     * 失败只记日志不阻断启动——与 {@code ConfigMigrations} 同一制式。
     *
     * <p>搬空的旧目录随手删掉:<b>目录不在 = 迁移完成</b>,下次启动整段直接跳过。
     * 这样不必另存一个标志文件,也不会为了一次性的事年年重扫所有会话日志。
     */
    public static void migrateLegacy() {
        Path root = numenRoot();
        int bound = migrateAssignments(root.resolve("providers.json"), "provider")
                + migrateAssignments(root.resolve("voice.json"), "voice");

        Path conv = root.resolve("conversations");
        Path mem = root.resolve("memory");
        int moved = 0;
        if (Files.isDirectory(conv) || Files.isDirectory(mem)) {
            moved += moveByPattern(conv, ".jsonl", CHAT);
            moved += moveByPattern(conv, ".stats.json", STATS);
            moved += moveByPattern(conv, ".inbox.jsonl", INBOX);
            // v1→v2 升级留下的备份也是这只同伴的东西:一起搬,将来跟着她一起被删。
            // 落点与 ConvoLog 自己的命名一致(chat.jsonl + ".v1.bak")。
            moved += moveByPattern(conv, ".jsonl.v1.bak", CHAT + ".v1.bak");
            moved += moveByPattern(mem, ".blocks.json", BLOCKS);
            bound += claimPersonaBindings();
            deleteIfEmpty(conv);
            deleteIfEmpty(mem);
        }
        if (moved > 0 || bound > 0) {
            Constants.LOG.info("[numen-home] 迁移完成:搬入 {} 个文件,收拢 {} 条绑定", moved, bound);
        }
    }

    /**
     * 人设绑定旧版记在会话日志的 {@code persona-change} 事件里(事件溯源),
     * 现在收进 binding.json。已经绑了的不动——迁移可重跑。
     */
    private static int claimPersonaBindings() {
        int n = 0;
        for (UUID uuid : known()) {
            if (binding(uuid).personaId() != null) {
                continue;
            }
            Path chat = chat(uuid);
            if (!Files.isRegularFile(chat)) {
                continue;
            }
            String personaId = com.dwinovo.numen.agent.llm.ConvoLog.atFile(chat).legacyPersonaId();
            if (personaId != null) {
                bind(uuid, binding(uuid).withPersona(personaId));
                n++;
            }
        }
        return n;
    }

    /** 空了才删——里面还有别人的东西就留着(也就留着下次再扫一遍,那是他自己放的)。 */
    private static void deleteIfEmpty(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            if (list.findAny().isPresent()) {
                return;
            }
            Files.delete(dir);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-home] 旧目录清理失败 {}: {}", dir, e.toString());
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
