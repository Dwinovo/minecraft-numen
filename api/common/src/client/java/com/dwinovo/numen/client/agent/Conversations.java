package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.conversation.Mentions;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.api.Delivery;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.data.JsonLibrary;
import com.dwinovo.numen.event.NumenEvents;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 这台机器上的会话,落在 {@code companions/conversations.json}(见 {@link CompanionHome#conversations})。
 *
 * <h2>只有会话这一个概念</h2>
 * 单聊是成员表长度为 1 的会话,不是另一种东西。所以这里没有一处问"这是单聊还是群聊"——
 * {@link #say} 对一个人和对三个人走的是同一条路,单成员时自动退化成今天的行为。
 *
 * <h2>落盘按需,不按人数</h2>
 * 主人改过它(拉了人、改了名)才存;没动过的不存。<b>不是"因为它是单聊所以不存",是"没人
 * 动过它所以没东西存"</b>——这条规则对所有会话一视同仁。读的时候有持久记录就用,没有就现构
 * 一个;话头对单成员恒等于那一个人,本来也没什么可存的。
 *
 * <h2>成员不做清理,读的时候按名册过滤</h2>
 * 同伴被遣散后她的 UUID 还留在成员表里。不去追着删是有意的:遣散只有"删一个目录"这一个动作,
 * 一旦要求它顺手改会话,就又多了一处"忘了改就留脏数据"的地方。
 *
 * <p>客户端主线程专用,与其余几个库同一制式。
 */
public final class Conversations extends JsonLibrary<Conversation> {

    private static Conversations instance;

    private Conversations(java.nio.file.Path file) {
        super(file);
    }

    public static Conversations instance() {
        if (instance == null) {
            instance = new Conversations(CompanionHome.conversations());
            instance.load();
        }
        return instance;
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

    /** 跟这一只说话的那个会话。存过就用存的,没存过现构一个——见类头"落盘按需"。 */
    public Conversation of(UUID companion) {
        return of(List.of(companion));
    }

    /** 成员表正好是这些人的那个会话;没有就现构一个(不落盘)。 */
    public Conversation of(List<UUID> members) {
        Set<UUID> want = new LinkedHashSet<>(members);
        for (Conversation c : list()) {
            if (new LinkedHashSet<>(c.members()).equals(want)) {
                return c;
            }
        }
        return Conversation.of(members);
    }

    /** 这只同伴在哪些会话里。 */
    public List<Conversation> containing(UUID companion) {
        List<Conversation> out = new ArrayList<>();
        for (Conversation c : list()) {
            if (c.has(companion)) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- 改(改了才落盘) ----

    /** 往会话里拉一个人。<b>没有"建群"这个动作</b>,拉进第二个人它自然就是多人会话。 */
    public Conversation pullIn(Conversation conv, UUID companion) {
        List<UUID> members = new ArrayList<>(conv.members());
        if (!members.contains(companion)) {
            members.add(companion);
        }
        return save(conv.withMembers(members));
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

    /** 主人动过它,从此它有持久记录。 */
    public Conversation save(Conversation conv) {
        putAndSave(conv);
        return conv;
    }

    // ---- 说 ----

    /**
     * 在这个会话里说一句话——<b>四个入口(Y 键、面板输入框、语音转写、外脑)共用的唯一一个</b>。
     *
     * <p>路由见 {@link Mentions}:点了名的醒,其余的旁听。旁听走 {@link EventTypes#TALK},
     * 是捎带投递,不会把人叫醒——这是群聊唯一的那条不变量。
     *
     * @return 说完之后的会话,以及有没有人收下
     */
    public Said say(Conversation conv, String text) {
        List<Mentions.Member> members = named(conv);
        if (members.isEmpty() || text == null || text.isBlank()) {
            return new Said(conv, false);
        }
        Mentions.Routing routing = Mentions.route(text, members, conv.floor());
        String heard = overheardLine(ownerName() + " → " + addressees(routing, members), text);
        boolean reached = false;
        for (Mentions.Member m : members) {
            Delivery d = routing.awake().contains(m.uuid())
                    ? NumenGateway.emit(m.uuid(), EventTypes.QUERY, text)
                    : NumenGateway.emit(m.uuid(), EventTypes.TALK, heard);
            reached |= d != Delivery.REJECTED;
        }
        Conversation next = conv.withFloor(routing.floor());
        boolean persisted = get(conv.id()) != null;
        if (persisted) {
            // 被叫醒的那几只从此在这个场面里,她们接下来说的话就属于这里
            for (UUID m : routing.awake()) {
                inConversation.put(m, conv.id());
            }
        }
        return new Said(persisted ? save(next) : next, reached);
    }

    /**
     * 说完之后。
     *
     * @param reached 至少有一只收下了。界面拿它判"要不要把主人这句话回显在聊天流里"
     */
    public record Said(Conversation conversation, boolean reached) {}

    /**
     * 她此刻在哪个会话里——最后一次是被哪个会话叫醒的。
     *
     * <p>这是整套设计里<b>唯一新增的状态</b>。它回答一件事:她说出口的话该让谁听见。
     * 人也是这样的——在哪个场合被搭话就在哪个场合回话。
     *
     * <p>只记<b>落过盘的会话</b>:没落盘的一定是单成员的(拉人就会存),
     * 而单成员会话根本没别人要告诉。会话态,不落盘——重进游戏后主人一开口就重新定下来。
     */
    private final java.util.Map<UUID, String> inConversation = new java.util.HashMap<>();

    /**
     * 她说出口的那一句,推给<b>叫醒她的那个会话</b>里的其他人。
     *
     * <p>她自己不推——那已经在她日志里了,再推一条就是同一句话的第二个出处。
     * 她同时在多个会话里时,这句话只属于其中一个场面——不串台。
     */
    public void heard(UUID speaker, String said) {
        if (said == null || said.isBlank()) {
            return;
        }
        Conversation conv = get(inConversation.get(speaker));
        if (conv == null) {
            return;   // 没人跟她说过话,或者那是个单成员会话:没人要告诉
        }
        String name = NumenRoster.instance().name(speaker);
        String line = overheardLine(name == null ? "?" : name, said);
        for (UUID m : membersAlive(conv)) {
            if (!m.equals(speaker)) {
                NumenGateway.emit(m, EventTypes.TALK, line);
            }
        }
    }

    // ---- 成员 ----

    /** 名册上还在的成员:遣散掉的那只不再出现,也不必回头改这份文件。 */
    public List<UUID> membersAlive(Conversation conv) {
        List<UUID> out = new ArrayList<>();
        for (UUID m : conv.members()) {
            if (NumenRoster.instance().name(m) != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** 还在的成员 + 她们的名字,喂给 {@link Mentions}。 */
    private List<Mentions.Member> named(Conversation conv) {
        List<Mentions.Member> out = new ArrayList<>();
        for (UUID m : membersAlive(conv)) {
            out.add(new Mentions.Member(m, NumenRoster.instance().name(m)));
        }
        return out;
    }

    // ---- 拼 ----

    /**
     * 旁听到的那一行。<b>经 {@link NumenEvents#entry} 这个唯一的构造口拼</b>——
     * {@code <event kind="talk" day t>} 的形状、属性转义、游戏内时间戳全由它统一盖上,
     * 这里不另写一份格式,也就不会跟别处跑偏。
     */
    private static String overheardLine(String speaker, String text) {
        Minecraft mc = Minecraft.getInstance();
        long dayTime = mc.level == null ? 0L : mc.level.getDayTime();
        return NumenEvents.entry(dayTime, EventTypes.TALK, Map.of(),
                "[" + speaker + "] " + text, System.currentTimeMillis(), false).text();
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

    private static String ownerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "主人" : mc.player.getGameProfile().getName();
    }
}
