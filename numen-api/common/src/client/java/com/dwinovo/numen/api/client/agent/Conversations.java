package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.brain.LegacyMigration;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 主人客户端上的会话:<b>成员表是服务端的</b>(路由、旁听、停止都在那头,见 {@code docs/group-chat.md}),这里读的是它推来的那份
 * ({@link RemoteCompanions#savedConversations})加名册;选中谁、置顶、文件夹、草稿、已读是这台机器的展示偏好,落在
 * {@code conversation_prefs.json},删了不丢她的任何东西。
 *
 * <h2>只有会话这一个概念</h2>
 * 单聊是成员表长度为 1 的会话,不是另一种东西。所以这里没有一处问"这是单聊还是群聊"。
 *
 * <h2>改会话:先按办成画,再让服务端判</h2>
 * 拉人、移人、改名、解散、说话都发给服务端办。界面马上要指着办完的那个会话(刚建的群、改过名的),等不起一个来回,所以这里按服务端会做的
 * 同一套纯函数({@link Conversation})先算出结果、记在 {@link PendingChanges},发出请求;服务端下一次推来真表时,pending 作废,以真表为准
 * ——办不成的,服务端也把真表推回来。新群的 id 由这里起,随请求带去。
 *
 * <p>客户端主线程专用。
 */
public final class Conversations {

    private static Conversations instance;

    private final Path prefsFile;
    private final Path legacyFile;

    /** 当前交互对象的 id(见 {@link #select});重进游戏还是对着上次那个。 */
    private String selectedId;
    /** 每个会话主人看到哪了(最后看到的那条的时间戳);左栏据此数未读。 */
    private final Map<String, Long> seen = new HashMap<>();
    /** 每个会话里打了一半没发的话(Telegram 的草稿):切走时留下,切回来拿回;左栏标"草稿"。 */
    private final Map<String, String> drafts = new HashMap<>();
    /** 置顶的会话(Telegram 左栏置顶),按置顶的先后排在最上面。 */
    private final List<String> pinned = new ArrayList<>();
    /** 会话分组(Telegram 的 Chat Folders):主人建的分组、选中哪个分组。 */
    private final ChatFolders folders = new ChatFolders();

    /** 已经按办成画了、服务端还没回真表的会话改动({@code null} = 解散了)。 */
    private final PendingChanges<Conversation> pending = new PendingChanges<>();

    private Conversations(Path prefsFile, Path legacyFile) {
        this.prefsFile = prefsFile;
        this.legacyFile = legacyFile;
    }

    public static Conversations instance() {
        if (instance == null) {
            instance = new Conversations(Homes.clientDir().resolve("conversation_prefs.json"),
                    LegacyMigration.conversationsFile(NumenPaths.config()));
            instance.load();
        }
        return instance;
    }

    // ---- 展示偏好的落盘 ----

    private JsonDoc doc(Path file) {
        return new JsonDoc(file, JsonDoc.Format.of(1), NumenIo.inline("numen-convo"));
    }

    /** 偏好文件还没有时,从旧版的会话文件里把这几样展示偏好接过来(旧文件原样留着,成员表归服务端)。 */
    private void load() {
        Path source = java.nio.file.Files.isRegularFile(prefsFile) ? prefsFile : legacyFile;
        try {
            doc(source).read().ifPresent(this::readPrefs);
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-convo] {} 读不了,用空的展示偏好: {}", source.getFileName(), unreadable.toString());
        }
    }

    private void readPrefs(JsonObject root) {
        selectedId = root.has("selected") && root.get("selected").isJsonPrimitive()
                ? root.get("selected").getAsString() : null;
        seen.clear();
        if (root.has("seen") && root.get("seen").isJsonObject()) {
            for (var e : root.getAsJsonObject("seen").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    seen.put(e.getKey(), e.getValue().getAsLong());
                }
            }
        }
        pinned.clear();
        if (root.has("pinned") && root.get("pinned").isJsonArray()) {
            for (var e : root.getAsJsonArray("pinned")) {
                if (e.isJsonPrimitive()) pinned.add(e.getAsString());
            }
        }
        drafts.clear();
        if (root.has("drafts") && root.get("drafts").isJsonObject()) {
            for (var e : root.getAsJsonObject("drafts").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    drafts.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        folders.read(root);
    }

    private void save() {
        JsonObject root = new JsonObject();
        if (selectedId != null) {
            root.addProperty("selected", selectedId);
        }
        if (!seen.isEmpty()) {
            JsonObject o = new JsonObject();
            for (var e : seen.entrySet()) {
                o.addProperty(e.getKey(), e.getValue());
            }
            root.add("seen", o);
        }
        if (!pinned.isEmpty()) {
            JsonArray a = new JsonArray();
            pinned.forEach(a::add);
            root.add("pinned", a);
        }
        if (!drafts.isEmpty()) {
            JsonObject o = new JsonObject();
            for (var e : drafts.entrySet()) {
                o.addProperty(e.getKey(), e.getValue());
            }
            root.add("drafts", o);
        }
        folders.write(root);
        doc(prefsFile).write(root);
    }

    // ---- 看到哪了 ----

    public long lastSeen(Conversation conv) {
        return seen.getOrDefault(conv.id(), 0L);
    }

    /** 主人看到了这一条(及之前的)。只在更晚时记并落盘——面板每帧都会调,不能每帧写盘。 */
    public void markSeen(Conversation conv, long ts) {
        if (ts > lastSeen(conv)) {
            seen.put(conv.id(), ts);
            save();
        }
    }

    // ---- 置顶 ----

    public boolean pinned(Conversation conv) {
        return pinned.contains(conv.id());
    }

    /** 置顶或取消置顶;新置顶的排在已置顶的最下面。落盘。 */
    public void togglePin(Conversation conv) {
        if (!pinned.remove(conv.id())) pinned.add(conv.id());
        save();
    }

    // ---- 分组 ----

    /** 标签条上的分组,按显示顺序。 */
    public List<String> folderIds() {
        return folders.ids();
    }

    /** 左栏此刻按哪个分组筛。 */
    public String folder() {
        return folders.active();
    }

    /** 切到这个分组。只在变了时落盘。 */
    public void selectFolder(String id) {
        if (folders.select(id)) save();
    }

    /** 这个会话在不在这个分组里;私聊/群聊按 {@link #soloOf} 分。 */
    public boolean inFolder(String folderId, Conversation conv) {
        return folders.includes(folderId, conv.id(), soloOf(conv) != null);
    }

    /** 主人建的那个分组;内置的是 null。 */
    public ChatFolders.Folder customFolder(String id) {
        return folders.get(id);
    }

    /** 建一个分组(起名、收哪些会话)。落盘。 */
    public void createFolder(String name, List<String> conversationIds) {
        folders.create(name, conversationIds);
        save();
    }

    /** 改分组的名字和收哪些会话。落盘。 */
    public void editFolder(String id, String name, List<String> conversationIds) {
        if (folders.edit(id, name, conversationIds)) save();
    }

    /** 删分组;里面的会话不受影响。落盘。 */
    public void deleteFolder(String id) {
        if (folders.delete(id)) save();
    }

    /** 把这个会话放进分组,已经在里面就拿出来。落盘。 */
    public void toggleInFolder(String folderId, Conversation conv) {
        if (folders.toggle(folderId, conv.id())) save();
    }

    // ---- 草稿 ----

    /** 这个会话里没发出去的那句;没有是空串。 */
    public String draft(Conversation conv) {
        return drafts.getOrDefault(conv.id(), "");
    }

    /** 记下(空串 = 清掉)。只在变了时落盘。 */
    public void setDraft(Conversation conv, String text) {
        String t = text == null ? "" : text;
        if (t.equals(draft(conv))) return;
        if (t.isEmpty()) drafts.remove(conv.id()); else drafts.put(conv.id(), t);
        save();
    }

    // ---- 当前交互对象 ----

    /** 选中这个会话:转盘确认、G 面板开在谁身上或切到谁,都走这里。落盘。 */
    public void select(Conversation conv) {
        if (conv == null || conv.id().equals(selectedId)) {
            return;
        }
        selectedId = conv.id();
        save();
    }

    /**
     * 选中的那个,此刻的最新一份——落过盘的从服务端那份里取(改名、拉人后成员表变了);"就他俩"
     * (id 就是她的 UUID)看她还在不在名册上;解散了的、遣散了的、别的存档里的,都是 null。
     */
    public Conversation selected() {
        if (selectedId == null) {
            return null;
        }
        Conversation saved = get(selectedId);
        if (saved != null) {
            return membersAlive(saved).isEmpty() ? null : saved;
        }
        UUID her;
        try {
            her = UUID.fromString(selectedId);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
        return NumenRoster.instance().name(her) != null ? of(her) : null;
    }

    // ---- 取 ----

    /** 服务端那份落过盘的会话,盖上还没回真表的改动。 */
    private List<Conversation> saved() {
        return pending.over(RemoteCompanions.savedConversations(), Conversation::id);
    }

    /** 落过盘的那条记录;没有是 null("就他俩"没有记录)。 */
    public Conversation get(String id) {
        for (Conversation c : saved()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 跟这一只说话的那个会话。成员表正好只有她的那条落盘记录有就用,没有就现构一个(不落盘)。
     *
     * <p>现构的那个 id 就是她的 UUID:面板每帧重列左栏、每次说话都现构一遍,身份得稳定才认得出
     * "还是同一个"。落盘的会话 id 永远另起,不会撞上。
     */
    public Conversation of(UUID companion) {
        for (Conversation c : saved()) {
            if (c.members().equals(List.of(companion))) {
                return c;
            }
        }
        return new Conversation(companion.toString(), null, List.of(companion), 0);
    }

    /**
     * 左栏列的那些:名册上每只一个,再加落过盘、还有活人的会话。按 id 去重——名册那一只的
     * "就他俩"要是落过盘,它已经在后一批里,不列两次。
     */
    public List<Conversation> all() {
        Map<String, Conversation> out = new LinkedHashMap<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            Conversation c = of(e.uuid());
            out.put(c.id(), c);
        }
        for (Conversation c : saved()) {
            if (!membersAlive(c).isEmpty()) {
                out.putIfAbsent(c.id(), c);
            }
        }
        // 置顶的按置顶先后排最上面,其余照原来的顺序
        List<Conversation> sorted = new ArrayList<>();
        for (String id : pinned) {
            Conversation c = out.remove(id);
            if (c != null) sorted.add(c);
        }
        sorted.addAll(out.values());
        return sorted;
    }

    /**
     * 就他俩时是她;落过盘的会话没有单一的主,null。背包、用量、目标、人设、遣散这些同伴专属的
     * 东西只在这时有主——判据是"落没落盘",不是人数:三人会话遣散掉两只,剩下的仍是一个会话。
     */
    public UUID soloOf(Conversation conv) {
        return tagOf(conv) == null ? conv.members().get(0) : null;
    }

    /** 名册上还不在这个会话里的那些:「＋ 拉人」列的就是她们。 */
    public List<UUID> pullable(Conversation conv) {
        List<UUID> out = new ArrayList<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (!conv.has(e.uuid())) {
                out.add(e.uuid());
            }
        }
        return out;
    }

    // ---- 改:先按办成画,再发给服务端判 ----

    /**
     * 往会话里拉一个人。<b>没有"建群"这个动作</b>,拉进第二个人它自然就是多人会话。
     *
     * <p>从"就他俩"拉人是另起一个会话:她俩的私聊照旧在,新会话另有 id。从落过盘的会话拉人是扩这个会话:
     * 名字、发言号、记录上的印都还是它的。
     */
    public Conversation pullIn(Conversation conv, UUID companion) {
        List<UUID> members = new ArrayList<>(conv.members());
        if (!members.contains(companion)) {
            members.add(companion);
        }
        boolean widens = tagOf(conv) != null;
        Conversation next = widens ? conv.withMembers(members) : Conversation.of(members);
        RemoteCompanions.send(new OwnerInput.Pull(conv.id(), companion, next.id()));
        pending.put(next.id(), next);
        return next;
    }

    /** 把一个人移出会话。 */
    public Conversation drop(Conversation conv, UUID companion) {
        List<UUID> members = new ArrayList<>(conv.members());
        members.remove(companion);
        Conversation next = conv.withMembers(members);
        RemoteCompanions.send(new OwnerInput.Drop(conv.id(), companion));
        pending.put(next.id(), next);
        return next;
    }

    /** 主人起的名(空白 = 退回拼成员名)。 */
    public Conversation rename(Conversation conv, String name) {
        Conversation next = conv.withName(name);
        RemoteCompanions.send(new OwnerInput.Rename(conv.id(), name));
        pending.put(next.id(), next);
        return next;
    }

    /**
     * 解散:删掉这条记录。说过的话留在成员各自的日志里,所以不丢历史。
     * 还站在这个场面里的成员退回"就他俩"是服务端的事。
     */
    public void dissolve(Conversation conv) {
        drafts.remove(conv.id());
        pinned.remove(conv.id());
        folders.forget(conv.id());
        save();
        RemoteCompanions.send(new OwnerInput.Dissolve(conv.id()));
        pending.put(conv.id(), null);
    }

    /**
     * 记录上盖的会话印:落过盘的会话是它的 id;没落盘的(单成员、没人动过)是 null = "就他俩"。
     * 面板归并成员日志时读印。
     */
    public String tagOf(Conversation conv) {
        return get(conv.id()) != null ? conv.id() : null;
    }

    // ---- 说 ----

    /**
     * 在这个会话里说一句话——Y 键、面板输入框、语音转写共用的唯一一个入口。点了名的醒,其余的旁听,谁醒谁听由服务端路由
     * (见 {@link Mentions})。
     *
     * @return 说完之后的会话(落过盘的发言号拨一),以及这句话有没有送出去(会话里有人、话不空)
     */
    public Said say(Conversation conv, String text) {
        if (membersAlive(conv).isEmpty() || text == null || text.isBlank()) {
            return new Said(conv, false);
        }
        RemoteCompanions.send(new OwnerInput.Say(conv.id(), text));
        Conversation next = tagOf(conv) != null ? conv.spoken() : conv;
        if (next != conv) {
            pending.put(next.id(), next);
        }
        return new Said(next, true);
    }

    /**
     * 说完之后。
     *
     * @param reached 话送出去了。界面拿它判"要不要把主人这句话回显在聊天流里"
     */
    public record Said(Conversation conversation, boolean reached) {}

    // ---- 停 ----

    /** 停止停全体:会话里每个成员的循环都停;单成员时就是她一个——同一条路。 */
    public void abort(Conversation conv) {
        RemoteCompanions.send(new OwnerInput.Stop(conv.id()));
    }

    /** 有谁能停。 */
    public boolean canAbort(Conversation conv) {
        for (UUID m : membersAlive(conv)) {
            if (RemoteCompanions.get(m).map(view -> view.status().canInterrupt()).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    // ---- 成员 ----

    /** 名册上还在的成员:遣散掉的那只不再出现,也不必回头改成员表。 */
    public List<UUID> membersAlive(Conversation conv) {
        List<UUID> out = new ArrayList<>();
        for (UUID m : conv.members()) {
            if (NumenRoster.instance().name(m) != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** 还在的成员 + 她们的名字:路由、`@` 补全、把 `@` 到的名字画亮,用的都是这一份。 */
    public List<Mentions.Member> named(Conversation conv) {
        List<Mentions.Member> out = new ArrayList<>();
        for (UUID m : membersAlive(conv)) {
            out.add(new Mentions.Member(m, NumenRoster.instance().name(m)));
        }
        return out;
    }
}
