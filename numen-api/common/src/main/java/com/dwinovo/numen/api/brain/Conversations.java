package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Audience;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.agent.conversation.Quote;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.JsonLibrary;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一位主人在这个世界里的会话:<b>一张成员表加一个名字</b>(见 {@code docs/group-chat.md})。成员表落在世界存档里
 * ({@code players/<主人>/conversations.json}),路由、旁听、停止都读这一份;选中谁、置顶、文件夹、草稿、已读是客户端自己的
 * 展示偏好,不在这里。群没有自己的对话历史:一个会话的记录是成员各自日志里盖着它印的那些行({@code History})。
 *
 * <p>同时是循环读的"谁在场"({@link CompanionLoop.Rooms}):她收到主人一句话那一刻得知道还有谁在听、这是第几句。
 *
 * <p>线程:服务端主线程。拨发言号、定场面、送话三步之间不许插进第二句,所以 {@link #say} 只在主线程上跑。
 */
public final class Conversations extends JsonLibrary<Conversation> implements CompanionLoop.Rooms {

    /** 名册那一侧:主人名下有哪几只,她们叫什么。遣散掉的名册上没有。 */
    public interface Roster {
        List<UUID> companions();

        /** 名册上这只的名字;不在名册上是 {@code null}。 */
        String name(UUID companion);
    }

    /** 会话 id 的长度上限。 */
    private static final int MAX_ID = 64;

    private final Roster roster;
    private final java.util.function.Function<UUID, java.util.Optional<CompanionLoop>> loops;
    private final java.util.function.Supplier<String> ownerName;

    /**
     * @param file  成员表落在哪、用哪条写车道
     * @param loops 找某只同伴的大脑;没有大脑的同伴听不到话
     */
    public Conversations(java.nio.file.Path file, com.dwinovo.numen.api.store.Io io, Roster roster,
                         java.util.function.Function<UUID, java.util.Optional<CompanionLoop>> loops,
                         java.util.function.Supplier<String> ownerName) {
        super(file, io);
        this.roster = roster;
        this.loops = loops;
        this.ownerName = ownerName;
        load();
    }

    // ---- 这个世界里每位主人的那一份 ----

    private static final Map<UUID, Conversations> OPEN = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(OPEN::clear);
    }

    /** 这位主人的会话;同一位主人永远是同一份。 */
    public static Conversations of(MinecraftServer server, UUID owner) {
        return OPEN.computeIfAbsent(owner, id -> {
            var home = Homes.of(server).worldOwner(id);
            CompanionRegistry registry = CompanionRegistry.get(server);
            return new Conversations(home.file("conversations.json"), home.io(), new Roster() {
                @Override
                public List<UUID> companions() {
                    return registry.ownedBy(id).stream().map(Map.Entry::getKey).toList();
                }

                @Override
                public String name(UUID companion) {
                    CompanionRegistry.Entry entry = registry.find(companion);
                    return entry == null || !id.equals(entry.owner()) ? null : entry.name();
                }
            }, CompanionLoops::get, () -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                return player == null ? "主人" : player.getGameProfile().getName();
            });
        });
    }

    @Override
    protected String logTag() {
        return "numen-convo";
    }

    @Override
    protected String idOf(Conversation entry) {
        return entry.id();
    }

    @Override
    protected Conversation readEntry(JsonObject o) {
        return Conversation.fromJson(o);
    }

    @Override
    protected JsonObject writeEntry(Conversation entry) {
        return entry.toJson();
    }

    // ---- 取 ----

    /**
     * 跟这一只说话的那个会话:成员表正好只有她的那条落盘记录有就用,没有就现构一个(不落盘)。现构的 id 就是她的 UUID,
     * 身份得稳定才认得出"还是同一个";落盘的会话 id 永远另起,不会撞上。
     */
    public Conversation of(UUID companion) {
        for (Conversation c : list()) {
            if (c.members().equals(List.of(companion))) {
                return c;
            }
        }
        return new Conversation(companion.toString(), null, List.of(companion), 0);
    }

    /** 会话 id 对应的会话:落盘的,或者"就他俩"(id 是名册上某只的 UUID);没有是 {@code null}。 */
    public Conversation find(String id) {
        Conversation saved = get(id);
        if (saved != null) {
            return saved;
        }
        try {
            UUID her = UUID.fromString(id);
            return roster.name(her) != null ? of(her) : null;
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    /** 会话在界面上叫什么:主人起了名就是那个,没起就拼成员名(成员此刻在名册上的名字)。 */
    public String titleOf(Conversation conv) {
        return conv.displayName(roster::name);
    }

    /**
     * 她此刻在的那个会话叫什么:{@code conversation} 是她的 {@link CompanionLoop#conversation()},{@code null} 是"就他俩"
     * (那就叫她的名字)。会话已经解散了是 {@code null}。
     */
    public String titleFor(UUID companion, String conversation) {
        Conversation conv = conversation == null ? of(companion) : get(conversation);
        return conv == null ? null : titleOf(conv);
    }

    /**
     * 主人指的是哪个会话:写的是会话的名字(忽略大小写)或 id。名字可能重名,所以给出全部对得上的。
     */
    public List<Conversation> matching(String titleOrId) {
        List<Conversation> out = new ArrayList<>();
        for (Conversation conv : all()) {
            if (conv.id().equals(titleOrId) || titleOf(conv).equalsIgnoreCase(titleOrId)) {
                out.add(conv);
            }
        }
        return out;
    }

    /** 名册上每只一个"就他俩",再加落过盘、还有活人的会话。按 id 去重。 */
    public List<Conversation> all() {
        Map<String, Conversation> out = new LinkedHashMap<>();
        for (UUID companion : roster.companions()) {
            Conversation c = of(companion);
            out.put(c.id(), c);
        }
        for (Conversation c : list()) {
            if (!membersAlive(c).isEmpty()) {
                out.putIfAbsent(c.id(), c);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** 落没落盘决定它有没有单一的主:落过盘的会话没有。 */
    public UUID soloOf(Conversation conv) {
        return tagOf(conv) == null ? conv.members().get(0) : null;
    }

    /** 记录上盖的会话印:落过盘的会话是它的 id;没落盘的是 {@code null} = "就他俩"。盖印和读印都问这一处。 */
    public String tagOf(Conversation conv) {
        return get(conv.id()) != null ? conv.id() : null;
    }

    /** 名册上还在的成员:遣散掉的那只不再出现,也不必回头改这份文件。 */
    public List<UUID> membersAlive(Conversation conv) {
        List<UUID> out = new ArrayList<>();
        for (UUID m : conv.members()) {
            if (roster.name(m) != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** 还在的成员加她们的名字:路由用的就是这一份。 */
    public List<Mentions.Member> named(Conversation conv) {
        List<Mentions.Member> out = new ArrayList<>();
        for (UUID m : membersAlive(conv)) {
            out.add(new Mentions.Member(m, roster.name(m)));
        }
        return out;
    }

    /** 这只同伴在哪些落过盘的会话里。 */
    private List<Conversation> containing(UUID companion) {
        List<Conversation> out = new ArrayList<>();
        for (Conversation c : list()) {
            if (c.has(companion)) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- 改(改了才落盘) ----

    /**
     * 往会话里拉一个人。没有"建群"这个动作,拉进第二个人它自然就是多人会话。从"就他俩"拉人是另起一个会话(她俩的私聊照旧在);
     * 从落过盘的会话拉人是扩这个会话。
     */
    public Conversation pullIn(Conversation conv, UUID companion) {
        return pullIn(conv, companion, UUID.randomUUID().toString());
    }

    /** 同上;从"就他俩"拉人时新群用 {@code newId}(主人的客户端起的,服务端核过没撞号)。 */
    public Conversation pullIn(Conversation conv, UUID companion, String newId) {
        List<UUID> members = new ArrayList<>(conv.members());
        if (!members.contains(companion)) {
            members.add(companion);
        }
        return save(tagOf(conv) != null ? conv.withMembers(members) : Conversation.of(newId, members));
    }

    /** 这个 id 能不能给一个新群:没有人用过,也不是名册上哪只同伴的号("就他俩"用同伴的 UUID 当 id)。 */
    public boolean isFreeId(String id) {
        if (id == null || id.isBlank() || id.length() > MAX_ID || get(id) != null) {
            return false;
        }
        try {
            return roster.name(UUID.fromString(id)) == null;
        } catch (IllegalArgumentException notUuid) {
            return true;
        }
    }

    /** 把一个人移出会话。 */
    public Conversation drop(Conversation conv, UUID companion) {
        List<UUID> members = new ArrayList<>(conv.members());
        members.remove(companion);
        return save(conv.withMembers(members));
    }

    /** 主人起的名(空白 = 退回拼成员名)。 */
    public Conversation rename(Conversation conv, String name) {
        return save(conv.withName(name));
    }

    /**
     * 解散:删掉这条记录。说过的话留在成员各自的日志里,不丢历史。还站在这个场面里的成员退回"就他俩"——
     * 不然她下一句话会盖一个已经不存在的印,哪个视图都看不见。
     */
    public void dissolve(Conversation conv) {
        remove(conv.id());
        for (UUID m : conv.members()) {
            loops.apply(m).filter(l -> conv.id().equals(l.conversation())).ifPresent(l -> l.inConversation(null));
        }
    }

    private Conversation save(Conversation conv) {
        putAndSave(conv);
        return conv;
    }

    // ---- 说 ----

    /** 说完之后。{@code reached}:至少有一只收下了。 */
    public record Said(Conversation conversation, boolean reached) {}

    /**
     * 在这个会话里说一句话——主人说话的唯一入口(面板输入框、快捷对话、语音转写、命令都经它)。点了名的醒,其余的旁听;
     * 旁听走 {@link EventTypes#TALK},是捎带投递,不会把人叫醒——群聊唯一的那条不变量。
     *
     * @param venue 主人是在哪儿说的:被叫醒的那几只回话回到那里
     */
    public Said say(Conversation conv, String text, Venue venue) {
        List<Mentions.Member> members = named(conv);
        if (members.isEmpty() || text == null || text.isBlank()) {
            return new Said(conv, false);
        }
        // 引的那句里可能有 @,那是别人当时的话,不算主人这次叫谁
        Mentions.Routing routing = Mentions.route(Quote.parse(text).body(), members);
        boolean persisted = get(conv.id()) != null;
        // 先拨号落盘,再定场面,最后送话:被叫醒的那几只从此在这个场面里——接下来她说的话属于这里,记录盖这个印;
        // 她收到这句话那一刻就得知道还有谁在听、这是第几句(audienceLine 读的就是落盘后的这份会话)。
        Conversation next = persisted ? save(conv.spoken()) : conv;
        String tag = tagOf(conv);
        for (UUID m : routing.awake()) {
            loops.apply(m).ifPresent(l -> l.inConversation(tag));
        }
        String heard = overheardLine(ownerName.get() + " → " + addressees(routing, members), text);
        boolean reached = false;
        for (Mentions.Member m : members) {
            var loop = loops.apply(m.uuid());
            if (loop.isEmpty()) {
                continue;
            }
            if (routing.awake().contains(m.uuid())) {
                loop.get().submitPrompt(text, venue);
            } else {
                loop.get().submitEvent(EventTypes.TALK, heard);
            }
            reached = true;
        }
        return new Said(next, reached);
    }

    /**
     * 她说出口的那一句,推给<b>叫醒她的那个会话</b>里的其他人。她自己不推——那已经在她日志里了。她同时在多个会话里时,
     * 这句话只属于其中一个场面,不串台。
     */
    public void heard(UUID speaker, String said) {
        if (said == null || said.isBlank()) {
            return;
        }
        Conversation conv = loops.apply(speaker).map(CompanionLoop::conversation).map(this::get).orElse(null);
        if (conv == null) {
            return;   // 没人跟她说过话,或者那是个单成员会话:没人要告诉
        }
        String name = roster.name(speaker);
        String line = overheardLine(name == null ? "?" : name, said);
        for (UUID m : membersAlive(conv)) {
            if (!m.equals(speaker)) {
                loops.apply(m).ifPresent(l -> l.submitEvent(EventTypes.TALK, line));
            }
        }
    }

    /**
     * 她被遣散了:告诉每个和她同过会话、还在的成员。她的日志跟着家一起没了,别人日志里旁听到的她的话还在——这条事件给那个缺口一个解释。
     * 同一个人和她同在几个会话里也只说一次。她走后只剩一个人(或没人)的群随即解散。
     */
    public void left(UUID gone, String name) {
        List<Conversation> hers = containing(gone);
        Set<UUID> tell = new LinkedHashSet<>();
        for (Conversation c : hers) {
            tell.addAll(membersAlive(c));
        }
        for (UUID m : tell) {
            loops.apply(m).ifPresent(l -> l.submitEvent(EventTypes.LEFT,
                    name + " was dismissed by the owner and is gone"));
        }
        for (Conversation c : hers) {
            if (dissolvesAfterLeaving(c, membersAlive(c).size())) {
                dissolve(c);
            }
        }
    }

    /** 群只剩一个人就不再是群。本来就只有一个成员的记录(落过盘的"就他俩")不在此列。 */
    static boolean dissolvesAfterLeaving(Conversation conv, int alive) {
        return conv.members().size() > 1 && alive <= 1;
    }

    // ---- 停 ----

    /** 停止停全体:会话里每个成员的循环都停;单成员时就是她一个——同一条路。 */
    public void abort(Conversation conv) {
        for (UUID m : membersAlive(conv)) {
            loops.apply(m).ifPresent(CompanionLoop::abort);
        }
    }

    // ---- 循环读的"谁在场" ----

    @Override
    public String audienceLine(UUID companion, String conversation) {
        if (conversation == null) {
            return "";
        }
        Conversation conv = get(conversation);
        if (conv == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (UUID m : membersAlive(conv)) {
            if (!m.equals(companion)) {
                names.add(roster.name(m));
            }
        }
        return names.isEmpty() ? ""
                : Audience.line(conv.turn(), NumenEvents.escape(String.join("、", names)));
    }

    // ---- 拼 ----

    /** 旁听到的那一行的<b>正文</b>:{@code [谁] 说了什么}。事件的壳由投递口盖。 */
    static String overheardLine(String speaker, String text) {
        return "[" + speaker + "] " + text;
    }

    /** 这句话喊的是谁——旁听的人得知道主人在跟谁说话。 */
    private static String addressees(Mentions.Routing routing, List<Mentions.Member> members) {
        List<String> names = new ArrayList<>();
        for (Mentions.Member m : members) {
            if (routing.awake().contains(m.uuid())) {
                names.add(m.name());
            }
        }
        return String.join("、", names);
    }
}
