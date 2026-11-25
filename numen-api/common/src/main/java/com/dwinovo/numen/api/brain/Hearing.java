package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.agent.conversation.Quote;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.sdk.EntityLabel;
import com.dwinovo.numen.api.seat.ServerSeats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 别人对她说的话怎么到她的座位:服务端收到(私聊、群里、聊天栏切进了会话、分享来的 keeper 在面板里说),<b>只在这一处</b>造成事件、
 * 送进创建者的座位({@link ServerSeats#hear}),她的大脑在创建者的客户端上。事件带着说话人的身份(玩家的唯一写法
 * {@code player@短码 (名字)})和说话的地方({@link Spoken});点了名的或私聊她的 keeper 是主人的话(会叫醒她),
 * 别的人的话与群里没点到她的是旁听({@link EventTypes#TALK},捎带投递,不叫醒)。
 *
 * <p>会话成员表只有 {@link Conversations} 那一张,谁在群里、谁被点到都读它。
 *
 * <p>线程:服务端主线程。
 */
public final class Hearing {

    /** 私聊这个说法在 {@code <query>} 上的写法。 */
    private static final String WHISPER = "whisper";
    private static final String GROUP = "group";

    private Hearing() {}

    /**
     * 原版 {@code /msg}(与 {@code /tell}、{@code /w})发给她的话。她是真玩家,私聊的包到她的"客户端"({@code FakeClient})。
     * 她的 keeper 的话是主人的话(她私信回);别的玩家的话是旁听。不读公屏:公屏上提到她不是跟她说话。
     *
     * @param sender     发话的玩家
     * @param senderName 发话的人此刻显示的名字
     */
    public static void whisper(NumenPlayer companion, UUID sender, String senderName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String who = EntityLabel.shown(sender, senderName);
        if (companion.usableBy(sender)) {
            ServerSeats.hear(companion.getUUID(), query(text, who, WHISPER, null),
                    new Spoken(sender, Venue.WHISPER, null));
        } else {
            ServerSeats.tell(companion.getUUID(), EventTypes.TALK, overheard(who + " → " + companion.getName().getString(), text));
        }
    }

    /**
     * 他在这个会话里说一句话:点了名的醒(是他对她说的话),其余的旁听。单聊(会话是"就他俩")只有她一个成员,话就是对她说的。
     * 面板、聊天栏切进会话、{@code /numen chat <会话> <话>} 走这一个入口。
     *
     * @return 没送出去的原因(没有这个会话、没有人在、话是空的);送出去了是 {@code null}
     */
    public static String say(MinecraftServer server, ServerPlayer speaker, Conversation conv, String text) {
        Conversations rooms = Conversations.of(server, speaker.getUUID());
        List<Mentions.Member> members = rooms.named(conv);
        if (members.isEmpty() || text == null || text.isBlank()) {
            return "nobody heard it";
        }
        // 引的那句里可能有 @,那是别人当时的话,不算他这次叫谁
        Mentions.Routing routing = Mentions.route(Quote.parse(text).body(), members);
        // 先拨号落盘再送话:她收到这句时,<audience> 上的发言号就是这一句的
        conv = rooms.spoke(conv);
        String tag = rooms.tagOf(conv);
        Spoken spoken = new Spoken(speaker.getUUID(), tag == null ? Venue.PANEL : Venue.CHAT, tag);
        String who = EntityLabel.shown(speaker.getUUID(), speaker.getGameProfile().getName());
        String heard = overheard(who + " → " + addressees(routing, members), text);
        for (Mentions.Member m : members) {
            if (routing.awake().contains(m.uuid())) {
                ServerSeats.hear(m.uuid(), query(text, who, tag == null ? null : GROUP, tag == null ? null : rooms.audienceLine(conv, m.uuid())), spoken);
            } else {
                ServerSeats.tell(m.uuid(), EventTypes.TALK, heard);
            }
        }
        return null;
    }

    /**
     * 她在群里说的一句,推给那个会话里的其他成员:她自己不推——那已经在她日志里了。她同时在多个会话里时,这句话只属于叫醒她的那一个。
     */
    public static void heard(MinecraftServer server, UUID table, Conversation conv, UUID speaker, String said) {
        Conversations rooms = Conversations.of(server, table);
        String name = rooms.titleFor(speaker, null);
        String line = overheard(name == null ? "?" : name, said);
        for (UUID member : rooms.membersAlive(conv)) {
            if (!member.equals(speaker)) {
                ServerSeats.tell(member, EventTypes.TALK, line);
            }
        }
    }

    /** 主人的话(会叫醒她)交给模型的那一条:带说话人的身份,私聊与群里还带说话的地方。 */
    private static EventQueue.Entry query(String text, String who, String venue, String audience) {
        long day = ServerSeats.dayTime();
        String body = venue == null ? EventQueue.query(text, day, who) : EventQueue.query(text, day, who, venue);
        if (audience != null) {
            body += "\n" + audience;
        }
        return new EventQueue.Entry(EventTypes.QUERY, body, System.currentTimeMillis(), false);
    }

    /** 旁听到的那一行的正文:{@code [谁] 说了什么}。事件的壳由投递口盖。 */
    static String overheard(String speaker, String text) {
        return "[" + speaker + "] " + text;
    }

    /** 这句话喊的是谁——旁听的人得知道在跟谁说话。 */
    private static String addressees(Mentions.Routing routing, List<Mentions.Member> members) {
        List<String> names = new ArrayList<>();
        for (Mentions.Member m : members) {
            if (routing.awake().contains(m.uuid())) {
                names.add(m.name());
            }
        }
        return String.join(", ", names);
    }
}
