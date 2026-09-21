package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.conversation.Mentions;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.api.Delivery;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.data.JsonLibrary;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 跟这一只说话的那个会话。成员表正好只有她的那条落盘记录有就用,没有就现构一个(不落盘)。
     *
     * <p>现构的那个 id 就是她的 UUID:面板每帧重列左栏、每次说话都现构一遍,身份得稳定才认得出
     * "还是同一个"。落盘的会话 id 永远另起(见 {@link #pullIn}),不会撞上。
     */
    public Conversation of(UUID companion) {
        for (Conversation c : list()) {
            if (c.members().equals(List.of(companion))) {
                return c;
            }
        }
        return new Conversation(companion.toString(), null, List.of(companion), List.of(), 0);
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
        for (Conversation c : list()) {
            if (!membersAlive(c).isEmpty()) {
                out.putIfAbsent(c.id(), c);
            }
        }
        return new ArrayList<>(out.values());
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

    /**
     * 这个会话此刻的最新一份——快捷键攥着的是转盘关上那一刻的快照,成员表、名字之后可能变了。
     * 落过盘的从库里取;"就他俩"(id 就是她的 UUID)看她还在不在名册上;落过盘又解散了的是 null。
     */
    public Conversation current(Conversation conv) {
        Conversation saved = get(conv.id());
        if (saved != null) {
            return membersAlive(saved).isEmpty() ? null : saved;
        }
        if (conv.members().size() == 1 && conv.id().equals(conv.members().get(0).toString())) {
            UUID her = conv.members().get(0);
            return NumenRoster.instance().name(her) != null ? of(her) : null;
        }
        return null;
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

    /**
     * 往会话里拉一个人。<b>没有"建群"这个动作</b>,拉进第二个人它自然就是多人会话。
     *
     * <p>从"就他俩"拉人是另起一个会话:她俩的私聊照旧在,新会话另有 id——就他俩的 id 是她的
     * UUID,落盘的会话不能占这个号,不然 {@link #tagOf} 会把她的私聊也认成落过盘的。
     * 从落过盘的会话拉人是扩这个会话:名字、发言号、记录上的印都还是它的。
     */
    public Conversation pullIn(Conversation conv, UUID companion) {
        List<UUID> members = new ArrayList<>(conv.members());
        if (!members.contains(companion)) {
            members.add(companion);
        }
        return save(tagOf(conv) != null ? conv.withMembers(members) : Conversation.of(members));
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
     * 解散:删掉这条记录。说过的话留在成员各自的日志里,所以不丢历史。
     * 还站在这个场面里的成员退回"就他俩"——不然她下一句话会盖一个已经不存在的印,哪个视图都看不见。
     */
    public void dissolve(Conversation conv) {
        remove(conv.id());
        for (UUID m : conv.members()) {
            AgentLoopRegistry.get(m)
                    .filter(l -> conv.id().equals(l.conversation()))
                    .ifPresent(l -> l.inConversation(null));
        }
    }

    /** 主人动过它,从此它有持久记录。 */
    public Conversation save(Conversation conv) {
        putAndSave(conv);
        return conv;
    }

    /**
     * 记录上盖的会话印:落过盘的会话是它的 id;没落盘的(单成员、没人动过)是 null = "就他俩"。
     * 盖印(送话时)和读印(面板归并时)都问这一处。
     */
    public String tagOf(Conversation conv) {
        return get(conv.id()) != null ? conv.id() : null;
    }

    // ---- 说 ----

    /**
     * 在这个会话里说一句话——<b>四个入口(Y 键、面板输入框、语音转写、外脑)共用的唯一一个</b>。
     *
     * <p>路由见 {@link Mentions}:点了名的醒,其余的旁听。旁听走 {@link EventTypes#TALK},
     * 是捎带投递,不会把人叫醒——这是群聊唯一的那条不变量。
     *
     * <p><b>整个作为一个单元在主线程上跑。</b>拨发言号、定场面、送话三步之间不许插进第二句:
     * 语音转写在别的线程回调,要是各自往主线程排队,连说两句就可能共一个发言号。不在主线程
     * 就把整个调用交给主线程,自己按"已交出"返回——和 {@link NumenGateway#emit} 的
     * {@code HANDED_OFF} 同一口径。
     *
     * @return 说完之后的会话,以及有没有人收下
     */
    public Said say(Conversation conv, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> say(conv, text));
            return new Said(conv, true);
        }
        List<Mentions.Member> members = named(conv);
        if (members.isEmpty() || text == null || text.isBlank()) {
            return new Said(conv, false);
        }
        Mentions.Routing routing = Mentions.route(text, members, conv.floor());
        boolean persisted = get(conv.id()) != null;
        // 先拨号落盘,再定场面,最后送话:被叫醒的那几只从此在这个场面里——接下来她说的话属于这里,
        // 记录盖这个印;她收到这句话那一刻就得知道还有谁在听、这是第几句
        // (EntityAgentLoop.audienceLine 读的就是落盘后的这份会话)。
        // 没落盘的会话盖 null = "就他俩",于是旧记录天然落在单聊视图里,不需要迁移。
        Conversation next = conv.withFloor(routing.floor());
        if (persisted) {
            next = save(next.spoken());
        }
        String tag = tagOf(conv);
        for (UUID m : routing.awake()) {
            AgentLoopRegistry.get(m).ifPresent(l -> l.inConversation(tag));
        }
        String heard = overheardLine(ownerName() + " → " + addressees(routing, members), text);
        boolean reached = false;
        for (Mentions.Member m : members) {
            Delivery d = routing.awake().contains(m.uuid())
                    ? NumenGateway.emit(m.uuid(), EventTypes.QUERY, text)
                    : NumenGateway.emit(m.uuid(), EventTypes.TALK, heard);
            reached |= d != Delivery.REJECTED;
        }
        return new Said(next, reached);
    }

    /**
     * 说完之后。
     *
     * @param reached 至少有一只收下了。界面拿它判"要不要把主人这句话回显在聊天流里"
     */
    public record Said(Conversation conversation, boolean reached) {}

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
        // "她在哪个会话里"只住在循环那一处，这里去问，不另存一份
        Conversation conv = AgentLoopRegistry.get(speaker)
                .map(EntityAgentLoop::conversation)
                .map(this::get)
                .orElse(null);
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

    /**
     * 她被遣散了:告诉每个和她同过会话、还在的成员。她的日志跟着家目录一起没了,别人日志里旁听到的
     * 她的话还在——这条事件给那个缺口一个解释。同一个人和她同在几个会话里也只说一次。
     * 成员表不改:读的时候按名册过滤,见类头。
     */
    public void left(UUID gone, String name) {
        java.util.Set<UUID> tell = new java.util.LinkedHashSet<>();
        for (Conversation c : containing(gone)) {
            tell.addAll(membersAlive(c));
        }
        if (tell.isEmpty()) {
            return;
        }
        // 只给正文:<event kind="left" day t> 的壳由投递口拼(EntityAgentLoop.submitEvent),这里拼一层就是两层
        for (UUID m : tell) {
            NumenGateway.emit(m, EventTypes.LEFT, name + " was dismissed by the owner and is gone");
        }
    }

    // ---- 停 ----

    /** 停止停全体:会话里每个成员的循环都停;单成员时就是她一个——同一条路。 */
    public void abort(Conversation conv) {
        for (UUID m : membersAlive(conv)) {
            AgentLoopRegistry.get(m).ifPresent(EntityAgentLoop::abort);
        }
    }

    /** 有谁能停。 */
    public boolean canAbort(Conversation conv) {
        for (UUID m : membersAlive(conv)) {
            if (AgentLoopRegistry.get(m).map(l -> l.status().canInterrupt()).orElse(false)) {
                return true;
            }
        }
        return false;
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

    /** 还在的成员 + 她们的名字:路由、`@` 补全、把 `@` 到的名字画亮,用的都是这一份。 */
    public List<Mentions.Member> named(Conversation conv) {
        List<Mentions.Member> out = new ArrayList<>();
        for (UUID m : membersAlive(conv)) {
            out.add(new Mentions.Member(m, NumenRoster.instance().name(m)));
        }
        return out;
    }

    // ---- 拼 ----

    /**
     * 旁听到的那一行的<b>正文</b>:{@code [谁] 说了什么}。{@code <event kind="talk" day t>} 的壳、转义、
     * 游戏内时间戳由投递口盖({@code EntityAgentLoop.submitEvent}),这里不拼——拼了就是两层壳,
     * 里面那层还被转义成一串实体符号。
     */
    private static String overheardLine(String speaker, String text) {
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

    private static String ownerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "主人" : mc.player.getGameProfile().getName();
    }
}
