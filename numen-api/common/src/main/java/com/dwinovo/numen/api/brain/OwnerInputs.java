package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillInjection;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * 主人经客户端做的、服务端管的事({@link OwnerInput})的唯一入口。上行的东西在网络线程上读懂、验过形状就投进信箱
 * ({@link #offer}),主线程每刻取几条处理({@link #tick});这里对每一条先问"这只同伴、这个会话是不是你用得了的"(问的是她的主人表;
 * 改主人表由 {@link Sharing} 问),再交给命令共用的那些入口——客户端什么判断都不替服务端做。
 *
 * <p>线程:{@link #offer} 任何线程,其余服务端主线程。
 */
public final class OwnerInputs {

    /** 每位玩家的信箱装多少条;满了的丢弃。 */
    private static final int MAILBOX = 256;
    /** 主线程每刻从每位玩家的信箱里最多处理几条。 */
    private static final int PER_TICK = 8;
    /** 斜杠命令的前缀。 */
    private static final String PREFIX = "/";

    private static final Mailbox<OwnerInput> BOX = new Mailbox<>(MAILBOX);

    static {
        ServerLifecycle.onStopped(BOX::clear);
    }

    private OwnerInputs() {}

    /**
     * 处理的结果。
     *
     * @param problem              没办成的原因(给日志);办成是 {@code null}
     * @param conversationsChanged 会话的成员表或名字变了,该把新的列表推给主人
     */
    public record Applied(String problem, boolean conversationsChanged) {

        static Applied done() {
            return new Applied(null, false);
        }

        static Applied changed() {
            return new Applied(null, true);
        }

        static Applied refused(String problem) {
            return new Applied(problem, false);
        }

        /** 改会话的请求没办成:客户端已经先按办成画了,真表照样推回去让它对齐。 */
        static Applied refusedResync(String problem) {
            return new Applied(problem, true);
        }
    }

    /** 投进这位玩家的信箱;满了不收。 */
    public static boolean offer(UUID player, OwnerInput input) {
        boolean taken = BOX.offer(player, input);
        if (!taken) {
            Constants.LOG.warn("[numen-net] the mailbox of {} is full; dropped one {}", player, input.getClass().getSimpleName());
        }
        return taken;
    }

    static void dropMailbox(UUID player) {
        BOX.drop(player);
    }

    /** 每个服务器刻一次:每位玩家的信箱里取几条,处理。 */
    public static void tick(MinecraftServer server) {
        BOX.drain(PER_TICK, (player, input) -> handle(server, player, input));
    }

    /**
     * 办一条,并把结果交代清楚:没办成的写进日志,会话变了的把新列表推回去。客户端的信箱走这一处。
     */
    public static Applied handle(MinecraftServer server, UUID owner, OwnerInput input) {
        Applied applied = apply(server, owner, input);
        if (applied.problem() != null) {
            Constants.LOG.info("[numen-net] {}'s {} did not go through: {}", owner, input.getClass().getSimpleName(),
                    applied.problem());
        }
        if (applied.conversationsChanged()) {
            Replies.conversations(server, owner);
        }
        return applied;
    }

    /** 办一条。{@code owner} 是发这条的玩家,不是客户端自称的谁。 */
    public static Applied apply(MinecraftServer server, UUID owner, OwnerInput input) {
        Conversations rooms = Conversations.of(server, owner);
        return switch (input) {
            case OwnerInput.Share share -> {
                com.mojang.authlib.GameProfile profile = share.player() == null ? null
                        : NumenPlayer.profileNamed(server, share.player().strip());
                if (profile == null) {
                    tell(server, owner, Component.translatable("argument.player.unknown"));
                    yield Applied.refused("no player by that name");
                }
                yield shared(server, owner, share.companion(), Sharing.Change.SHARE, profile.getName(),
                        Sharing.share(server, owner, share.companion(), profile.getId()));
            }
            case OwnerInput.Unshare unshare -> shared(server, owner, unshare.companion(),
                    owner.equals(unshare.person()) ? Sharing.Change.LEAVE : Sharing.Change.UNSHARE,
                    NumenPlayer.playerName(server, unshare.person()),
                    Sharing.unshare(server, owner, unshare.companion(), unshare.person()));
            case OwnerInput.Pull pull -> {
                Conversation conv = conversation(rooms, owner, pull.conversation(), server);
                if (conv == null || !usable(server, owner, pull.companion())) {
                    yield Applied.refusedResync("no such conversation or companion");
                }
                // 从"就他俩"拉人要起一个新群,号由客户端起,核过没撞号才用
                String newId = pull.newId() == null ? UUID.randomUUID().toString() : pull.newId();
                if (rooms.get(conv.id()) == null && !rooms.isFreeId(newId)) {
                    yield Applied.refusedResync("that conversation id is taken");
                }
                rooms.pullIn(conv, pull.companion(), newId);
                yield Applied.changed();
            }
            case OwnerInput.Drop drop -> {
                Conversation conv = rooms.get(drop.conversation());
                if (conv == null) {
                    yield Applied.refusedResync("no such conversation");
                }
                rooms.drop(conv, drop.companion());
                yield Applied.changed();
            }
            case OwnerInput.Rename rename -> {
                Conversation conv = rooms.get(rename.conversation());
                if (conv == null) {
                    yield Applied.refusedResync("no such conversation");
                }
                rooms.rename(conv, rename.name());
                yield Applied.changed();
            }
            case OwnerInput.Dissolve dissolve -> {
                Conversation conv = rooms.get(dissolve.conversation());
                if (conv == null) {
                    yield Applied.refusedResync("no such conversation");
                }
                rooms.dissolve(conv);
                yield Applied.changed();
            }
        };
    }

    /**
     * 改主人表的结局:能不能改由 {@link Sharing} 判过了。表上有她、下令的人也够格的,结局和命令一样说给他听;够不上的
     * (没有这只同伴、他不是创建者——面板不给这个入口,只会是伪造或竞态)只进日志。
     */
    private static Applied shared(MinecraftServer server, UUID owner, UUID companion, Sharing.Change change,
                                  String player, Sharing.Outcome outcome) {
        if (outcome == Sharing.Outcome.NO_SUCH_COMPANION || outcome == Sharing.Outcome.NOT_CREATOR) {
            return Applied.refused(outcome.name());
        }
        tell(server, owner, Sharing.told(outcome, change, CompanionRegistry.get(server).find(companion).name(), player));
        return outcome == Sharing.Outcome.DONE ? Applied.done() : Applied.refused(outcome.name());
    }

    private static void tell(MinecraftServer server, UUID owner, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    /** 这个会话:落过盘的,或者"就他俩";而且成员里没有他用不了的同伴。 */
    private static Conversation conversation(Conversations rooms, UUID owner, String id, MinecraftServer server) {
        Conversation conv = id == null ? null : rooms.find(id);
        if (conv == null) {
            return null;
        }
        for (UUID member : conv.members()) {
            CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(member);
            if (entry != null && !entry.usableBy(owner)) {
                return null;
            }
        }
        return conv;
    }

    /** 这只同伴他用不用得了:他在她的主人表上。 */
    private static boolean usable(MinecraftServer server, UUID owner, UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        return entry != null && entry.usableBy(owner);
    }
}
