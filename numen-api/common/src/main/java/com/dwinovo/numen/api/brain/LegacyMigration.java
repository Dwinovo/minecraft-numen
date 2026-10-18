package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.store.Trees;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 旧版本留在主人客户端 {@code config/numen/} 里的东西,搬进服务端的位置(单人游戏与局域网房主:服务端和客户端在同一台机器上,
 * 直接读磁盘,不过网络)。三条规矩:
 * <ul>
 *   <li><b>复制,不移动</b>:旧的原样留着,以后的版本再删;</li>
 *   <li><b>只做一次</b>:目标已经在就是迁过了(或本来就有),不再碰;</li>
 *   <li><b>可重来</b>:先复制到 {@code <目标>.migrating},逐文件核对,再原子改名成目标。中途崩溃留下的半成品下次先清掉重来,
 *       目标要么不在、要么完整。</li>
 * </ul>
 * 旧日志不在这里升级:搬过去的是原样的字节,服务端大脑打开它时按它自己的版本整份升级(留备份)。
 *
 * <p>专用服务器上旧客户端的数据没法这样读到,不在这里:那里的同伴从空白开始。
 */
public final class LegacyMigration {

    /** 旧家里不搬的:世界归属标记(世界自己就是归属),以及没有人再读的 {@code blocks.json}。 */
    private static final Set<String> LEFT_BEHIND = Set.of("world", "blocks.json");
    /** 旧绑定里声线、皮肤的 id 是旧客户端库里的,新库认不得;它们的新名字由 {@link LegacyLooks} 写。 */
    private static final List<String> CLIENT_BINDINGS = List.of("voice", "skin");
    private static final String STAGING = ".migrating";

    private LegacyMigration() {}

    /** 旧版放会话名册(连同展示偏好)的文件:{@code <config/numen>/companions/conversations.json}。 */
    public static Path conversationsFile(Path legacyRoot) {
        return legacyRoot.resolve("companions").resolve("conversations.json");
    }

    /**
     * 她的家:旧的 {@code companions/<uuid>/}(对话日志及其备份、收件箱、台账、目标、札记、绑定)搬到世界里她的家。
     *
     * @return 这一次搬了;没有旧的、或世界里已经有她的家是 {@code false}
     */
    public static boolean companion(Path legacyHome, Path worldHome) {
        if (!Files.isDirectory(legacyHome)) {
            return false;
        }
        boolean moved = stage(worldHome, staging -> {
            Trees.copy(legacyHome, staging, name -> !LEFT_BEHIND.contains(name));
            keepOnlyWhatTheLoopReads(staging.resolve("binding.json"));
            verify(legacyHome, staging, "binding.json");
        });
        if (moved) {
            Constants.LOG.info("[numen-migrate] 同伴的家 {} 搬进了世界", legacyHome.getFileName());
        }
        return moved;
    }

    /**
     * 主人的主动性档位:旧版存在客户端的 {@code ui.json} 里(全局一个),搬到服务器上他的偏好文件。目标已经在就不动。
     *
     * @param legacyRoot 旧的 {@code config/numen/}
     * @param prefsFile  服务器上他的偏好文件
     * @return 这一次搬了
     */
    public static boolean initiative(Path legacyRoot, Path prefsFile) {
        Path ui = legacyRoot.resolve("ui.json");
        if (Files.exists(prefsFile) || !Files.isRegularFile(ui)) {
            return false;
        }
        JsonObject legacy = new JsonDoc(ui, JsonDoc.Format.of(1), Io.direct(e -> { })).read().orElse(null);
        if (legacy == null || !legacy.has("initiative")) {
            return false;
        }
        JsonObject prefs = new JsonObject();
        prefs.addProperty("initiative", legacy.get("initiative").getAsInt());
        new JsonDoc(prefsFile, JsonDoc.Format.of(1), Io.direct(e -> {
            throw new StoreException(e.getMessage(), e);
        })).write(prefs);
        return true;
    }

    /**
     * 旧版服务端为主人离线攒的事件({@code <世界>/data/numen_event_outbox.dat}):读成她们各自该进收件箱的条目,条目原样——
     * 类型、事发时刻、急不急都留着,她不会把"三小时前的事"当成刚发生的。读完把文件改名成 {@code .migrated},
     * 只做一次。读不通的当作没有,文件留着。
     *
     * @return 各只同伴攒下的条目,按事发先后;没有旧文件是空表
     */
    public static Map<UUID, List<EventQueue.Entry>> outbox(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<UUID, List<EventQueue.Entry>> out = new LinkedHashMap<>();
        try {
            CompoundTag boxes = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    .getCompound("data").getCompound("outboxes");
            for (String key : boxes.getAllKeys()) {
                UUID companion = UUID.fromString(key);
                List<EventQueue.Entry> entries = new ArrayList<>();
                ListTag list = boxes.getList(key, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag e = list.getCompound(i);
                    entries.add(new EventQueue.Entry(e.getString("type"), e.getString("text"), e.getLong("ts"),
                            e.getBoolean("urgent")));
                }
                entries.sort(Comparator.comparingLong(EventQueue.Entry::ts));
                out.put(companion, entries);
            }
            Files.move(file, file.resolveSibling(file.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | IllegalArgumentException unreadable) {
            Constants.LOG.warn("[numen-migrate] 旧版的离线事件 {} 读不通,留着不动: {}", file, unreadable.toString());
            return Map.of();
        }
        Constants.LOG.info("[numen-migrate] 旧版的离线事件 {} 条进了收件箱", out.values().stream().mapToInt(List::size).sum());
        return out;
    }

    /**
     * 会话成员表:旧的全局文件里属于这个世界的会话,写进世界里这位主人的会话文件。选中、置顶、文件夹、草稿、已读是客户端的
     * 展示偏好,留在旧文件里由客户端读。
     *
     * @param inThisWorld 这个会话是不是这个世界的(成员是这个世界的同伴)
     * @return 这一次写了;目标已经在、或没有这个世界的会话是 {@code false}
     */
    public static boolean conversations(Path legacyFile, Predicate<Conversation> inThisWorld, Path worldFile) {
        if (Files.exists(worldFile) || !Files.isRegularFile(legacyFile)) {
            return false;
        }
        JsonObject legacy = new JsonDoc(legacyFile, JsonDoc.Format.of(1), Io.direct(e -> { })).read().orElse(null);
        if (legacy == null || !(legacy.get("entries") instanceof JsonArray entries)) {
            return false;
        }
        JsonArray mine = new JsonArray();
        for (JsonElement e : entries) {
            Conversation conv = e.isJsonObject() ? Conversation.fromJson(e.getAsJsonObject()) : null;
            if (conv != null && inThisWorld.test(conv)) {
                mine.add(conv.toJson());
            }
        }
        if (mine.isEmpty()) {
            return false;
        }
        JsonObject root = new JsonObject();
        root.add("entries", mine);
        new JsonDoc(worldFile, JsonDoc.Format.of(1), Io.direct(e -> {
            throw new StoreException(e.getMessage(), e);
        })).write(root);
        return true;
    }

    // ---- 小件 ----

    /** 一个目录整个搬过去:目标不在、旧的在才搬。 */
    public static boolean tree(Path legacy, Path target) {
        return Files.isDirectory(legacy) && stage(target, staging -> {
            Trees.copy(legacy, staging);
            verify(legacy, staging, null);
        });
    }

    /** 一个文件搬过去:目标不在、旧的在才搬;先复制到暂存再改名,目标要么不在、要么完整。 */
    public static boolean file(Path legacy, Path target) {
        if (Files.exists(target) || !Files.isRegularFile(legacy)) {
            return false;
        }
        Path staging = target.resolveSibling(target.getFileName() + STAGING);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(legacy, staging, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreException("搬不动 " + legacy + ": " + e, e);
        }
        place(staging, target);
        return true;
    }

    /**
     * 目标不在才做:{@code fill} 把内容放进目标旁边的暂存目录,放完整个原子改名成目标。中途崩溃留下的半成品下次先清掉重来,
     * 目标要么不在、要么完整。
     *
     * @return 这一次做了;目标已经在(迁过了,或本来就有)是 {@code false}
     */
    public static boolean stage(Path target, java.util.function.Consumer<Path> fill) {
        if (Files.exists(target)) {
            return false;
        }
        Path staging = staging(target);
        fill.accept(staging);
        place(staging, target);
        return true;
    }

    private static Path staging(Path target) {
        Path staging = target.resolveSibling(target.getFileName() + STAGING);
        Trees.delete(staging);   // 上次崩在半路留下的
        return staging;
    }

    /** 绑定里只留档案和人设(声线、皮肤的旧 id 在新库里没有意义,见 {@link LegacyLooks})。留不下什么就不要这个文件。 */
    private static void keepOnlyWhatTheLoopReads(Path binding) {
        if (!Files.isRegularFile(binding)) {
            return;
        }
        JsonDoc doc = new JsonDoc(binding, JsonDoc.Format.of(1), Io.direct(e -> {
            throw new StoreException(e.getMessage(), e);
        }));
        JsonObject o = doc.read().orElse(new JsonObject());
        CLIENT_BINDINGS.forEach(o::remove);
        if (o.entrySet().isEmpty()) {
            doc.delete();
        } else {
            doc.write(o);
        }
    }

    /** 复制出来的每个文件和旧的一样大;{@code rewritten} 是特意改写过的那一个,不比。 */
    private static void verify(Path from, Path copy, String rewritten) {
        try (Stream<Path> walk = Files.walk(copy)) {
            for (Path made : walk.filter(Files::isRegularFile).toList()) {
                Path relative = copy.relativize(made);
                if (relative.toString().equals(rewritten)) {
                    continue;
                }
                Path source = from.resolve(relative.toString());
                if (!Files.isRegularFile(source) || Files.size(source) != Files.size(made)) {
                    throw new StoreException("复制出来的 " + made + " 和旧的对不上");
                }
            }
        } catch (IOException e) {
            throw new StoreException("核对不了 " + copy + ": " + e, e);
        }
    }

    private static void place(Path staging, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StoreException("放不进 " + target + ": " + e, e);
        }
    }
}
