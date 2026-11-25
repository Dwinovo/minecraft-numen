package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.seat.ServerSeats;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.sdk.EntityLabel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 改她的主人表——<b>只有这一处</b>(见 {@code docs/adr/runtime/0006-keepers-ownership-use-provenance.md} 第 5、6 条)。
 * 创建者加人、移人;被分享的人只能把自己移出去;创建者移不掉。可以加此刻不在线的玩家;分享不需要对方确认。
 *
 * <p>表本身只存在名册里({@link CompanionRegistry#share}、{@link CompanionRegistry#unshare}),这里在改完之后把后果收齐:
 * 重算她的出勤(多了一位在场的主人她就该在线,少了最后一位在场的就收拾、登出)、把新名册推给表上的每一位与少了她的那一位(名册的行带着整张主人表)、
 * 给她一条事件(多了哪位主人、少了哪位)、被移出的人绑的那几项退回未绑定({@link CompanionBinding#releaseBoundBy})并告诉还在的主人、
 * 他的会话与聊天栏里相应收拾、她挂着的征询在他那边补上或收起,并告诉当事的人。
 *
 * <p>线程:服务端主线程。
 */
public final class Sharing {

    private Sharing() {}

    /** 改表的结局。 */
    public enum Outcome {
        /** 表变了,后果都收齐了。 */
        DONE,
        /** 名册上没有这只同伴。 */
        NO_SUCH_COMPANION,
        /** 下令的人不是她的创建者(把自己移出去除外)。 */
        NOT_CREATOR,
        /** 要加的不是玩家,是一只同伴。 */
        NOT_A_PLAYER,
        /** 表没有变:他本来就在表上(创建者自己也在),或者本来就不在分享给的人里(创建者移不掉)。 */
        UNCHANGED
    }

    /** 创建者 {@code by} 把她分享给 {@code person}。 */
    public static Outcome share(MinecraftServer server, UUID by, UUID companion, UUID person) {
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(companion);
        if (entry == null) {
            return Outcome.NO_SUCH_COMPANION;
        }
        if (!entry.owner().equals(by)) {
            return Outcome.NOT_CREATOR;
        }
        if (registry.find(person) != null) {
            return Outcome.NOT_A_PLAYER;
        }
        if (!registry.share(companion, person)) {
            return Outcome.UNCHANGED;
        }
        Companions.reconcileCompanion(server, companion);
        tellHer(server, companion, shown(server, person) + " is now one of your owners: " + shown(server, by)
                + " shared you with them. From now on their word is an owner's word to you.");
        Companions.syncRosterToKeepers(server, registry.find(companion).keepers());
        ServerPlayer added = server.getPlayerList().getPlayer(person);
        if (added != null) {
            NumenPlayer body = NumenPlayer.findByUuid(server, companion);
            if (body != null) {
                ConsentDesk.of(body).keeperJoined(added);
            }
            added.sendSystemMessage(Component.translatable(Keys.NOTICE_SHARED_WITH_YOU,
                    NumenPlayer.playerName(server, by), entry.name()));
        }
        return Outcome.DONE;
    }

    /**
     * 不再分享给 {@code person}:创建者收回({@code by} 是创建者),或者他自己退出({@code by} 就是 {@code person})。
     */
    public static Outcome unshare(MinecraftServer server, UUID by, UUID companion, UUID person) {
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(companion);
        if (entry == null) {
            return Outcome.NO_SUCH_COMPANION;
        }
        boolean left = by.equals(person);
        if (!left && !entry.owner().equals(by)) {
            return Outcome.NOT_CREATOR;
        }
        if (!registry.unshare(companion, person)) {
            return Outcome.UNCHANGED;
        }
        ServerPlayer removed = server.getPlayerList().getPlayer(person);
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (removed != null && body != null) {
            ConsentDesk.of(body).keeperLeft(removed);   // 趁她还在世界里:她挂着的那条请求在他那边收起
        }
        Companions.reconcileCompanion(server, companion);
        tellHer(server, companion, shown(server, person) + " is no longer one of your owners: " + (left
                ? "they gave you back." : shown(server, by) + " stopped sharing you with them."));
        Conversations.of(server, person).withdrawn(companion, entry.name());
        Replies.conversations(server, person);
        Companions.syncRosterToKeepers(server, entry.keepers());   // 改表之前的那一份:还在的各位,加上少了她的那一位
        ServerPlayer told = server.getPlayerList().getPlayer(left ? entry.owner() : person);
        if (told != null) {
            told.sendSystemMessage(left
                    ? Component.translatable(Keys.NOTICE_KEEPER_LEFT, NumenPlayer.playerName(server, person), entry.name())
                    : Component.translatable(Keys.NOTICE_UNSHARED_FROM_YOU, NumenPlayer.playerName(server, by), entry.name()));
        }
        return Outcome.DONE;
    }

    /** 下令的人要的是哪一种改动。 */
    public enum Change {
        /** 创建者加人。 */
        SHARE,
        /** 创建者移人。 */
        UNSHARE,
        /** 被分享的人自己退出。 */
        LEAVE
    }

    /**
     * 改表的结局说给下令的人的那句话:命令回在命令的回执里,面板的上行({@link OwnerInput.Share}、{@link OwnerInput.Unshare})回在他的聊天里,
     * 同一句。
     *
     * @param companion 她的名字
     * @param player    被加、被移的那位玩家的名字(退出时不用)
     */
    public static Component told(Outcome outcome, Change change, String companion, String player) {
        boolean done = outcome == Outcome.DONE;
        return switch (change) {
            case SHARE -> outcome == Outcome.NOT_A_PLAYER ? Component.translatable(Keys.COMMAND_SHARE_NOT_PLAYER, player)
                    : Component.translatable(done ? Keys.COMMAND_SHARED : Keys.COMMAND_SHARE_UNCHANGED, companion, player);
            case UNSHARE -> Component.translatable(done ? Keys.COMMAND_UNSHARED : Keys.COMMAND_NOT_SHARED, companion, player);
            case LEAVE -> Component.translatable(done ? Keys.COMMAND_LEFT : Keys.COMMAND_NONE_SHARED_WITH_YOU, companion);
        };
    }

    /** 这位创建者名下、或分享给这个人的(不含他自己创建的)叫这个名字的同伴,大小写不计,同名的都算。 */
    public static List<UUID> named(MinecraftServer server, UUID person, String name, boolean created) {
        return CompanionRegistry.get(server).usableBy(person).stream()
                .filter(e -> e.getValue().owner().equals(person) == created && e.getValue().name().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getKey).toList();
    }

    /** 关系变了要让她知道:进她的收件箱,捎带投递(她不在世界里也收得到)。 */
    private static void tellHer(MinecraftServer server, UUID companion, String text) {
        ServerSeats.tell(companion, EventTypes.KEEPERS, text);
    }

    private static String shown(MinecraftServer server, UUID player) {
        return EntityLabel.shown(player, NumenPlayer.playerName(server, player));
    }
}
