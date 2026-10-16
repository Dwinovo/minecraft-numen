package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillInjection;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * 主人经客户端做的事({@link OwnerInput})到服务端大脑的唯一入口。上行的东西在网络线程上读懂、验过形状就投进信箱
 * ({@link #offer}),主线程每刻取几条处理({@link #tick});这里对每一条先问"这只同伴、这个会话是不是你的",再交给
 * 聊天、命令共用的那些入口——客户端什么判断都不替服务端做。
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
    }

    /** 投进这位玩家的信箱;满了不收。 */
    public static boolean offer(UUID player, OwnerInput input) {
        boolean taken = BOX.offer(player, input);
        if (!taken) {
            Constants.LOG.warn("[numen-net] {} 的信箱满了,丢弃一条 {}", player, input.getClass().getSimpleName());
        }
        return taken;
    }

    static void dropMailbox(UUID player) {
        BOX.drop(player);
    }

    /** 每个服务器刻一次:每位玩家的信箱里取几条,处理;会话变了的把新列表推回去。 */
    public static void tick(MinecraftServer server) {
        BOX.drain(PER_TICK, (player, input) -> {
            Applied applied = apply(server, player, input);
            if (applied.problem() != null) {
                Constants.LOG.info("[numen-net] {} 的 {} 没办成: {}", player, input.getClass().getSimpleName(),
                        applied.problem());
            }
            if (applied.conversationsChanged()) {
                Replies.conversations(server, player);
            }
        });
    }

    /** 办一条。{@code owner} 是发这条的玩家,不是客户端自称的谁。 */
    public static Applied apply(MinecraftServer server, UUID owner, OwnerInput input) {
        Conversations rooms = Conversations.of(server, owner);
        return switch (input) {
            case OwnerInput.Say say -> {
                Conversation conv = conversation(rooms, owner, say.conversation(), server);
                if (conv == null) {
                    yield Applied.refused("no such conversation");
                }
                yield rooms.say(conv, say.text()).reached() ? Applied.done() : Applied.refused("nobody heard it");
            }
            case OwnerInput.Command command -> loop(server, owner, command.companion(),
                    brain -> brain.submitCommand(command.echo(), skillBody(command.echo())));
            case OwnerInput.Stop stop -> {
                Conversation conv = conversation(rooms, owner, stop.conversation(), server);
                if (conv == null) {
                    yield Applied.refused("no such conversation");
                }
                rooms.abort(conv);
                yield Applied.done();
            }
            case OwnerInput.Compact compact -> loop(server, owner, compact.companion(), CompanionLoop::requestCompact);
            case OwnerInput.Clear clear -> loop(server, owner, clear.companion(), CompanionLoop::requestClearContext);
            case OwnerInput.SetGoal goal -> loop(server, owner, goal.companion(), brain ->
                    brain.setGoal(GoalState.of(goal.objective(), System.currentTimeMillis()), goal.echo()));
            case OwnerInput.ClearGoal goal -> loop(server, owner, goal.companion(), brain -> brain.clearGoal(null));
            case OwnerInput.SetProvider p -> loop(server, owner, p.companion(), brain -> brain.setProvider(p.provider()));
            case OwnerInput.SetPersona p -> loop(server, owner, p.companion(), brain -> brain.setPersona(p.persona()));
            case OwnerInput.Speaking s -> {
                if (!owns(server, owner, s.companion())) {
                    yield Applied.refused("not your companion");
                }
                CompanionLoops.voicePlaying(s.companion(), s.playing());
                yield Applied.done();
            }
            case OwnerInput.Pull pull -> {
                Conversation conv = conversation(rooms, owner, pull.conversation(), server);
                if (conv == null || !owns(server, owner, pull.companion())) {
                    yield Applied.refused("no such conversation or companion");
                }
                rooms.pullIn(conv, pull.companion());
                yield Applied.changed();
            }
            case OwnerInput.Drop drop -> {
                Conversation conv = rooms.get(drop.conversation());
                if (conv == null) {
                    yield Applied.refused("no such conversation");
                }
                rooms.drop(conv, drop.companion());
                yield Applied.changed();
            }
            case OwnerInput.Rename rename -> {
                Conversation conv = rooms.get(rename.conversation());
                if (conv == null) {
                    yield Applied.refused("no such conversation");
                }
                rooms.rename(conv, rename.name());
                yield Applied.changed();
            }
            case OwnerInput.Dissolve dissolve -> {
                Conversation conv = rooms.get(dissolve.conversation());
                if (conv == null) {
                    yield Applied.refused("no such conversation");
                }
                rooms.dissolve(conv);
                yield Applied.changed();
            }
        };
    }

    /** 这个会话:落过盘的,或者"就他俩";而且成员里没有别人的同伴。 */
    private static Conversation conversation(Conversations rooms, UUID owner, String id, MinecraftServer server) {
        Conversation conv = id == null ? null : rooms.find(id);
        if (conv == null) {
            return null;
        }
        for (UUID member : conv.members()) {
            CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(member);
            if (entry != null && !owner.equals(entry.owner())) {
                return null;
            }
        }
        return conv;
    }

    private static boolean owns(MinecraftServer server, UUID owner, UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        return entry != null && owner.equals(entry.owner());
    }

    private static Applied loop(MinecraftServer server, UUID owner, UUID companion,
                                java.util.function.Consumer<CompanionLoop> action) {
        if (!owns(server, owner, companion)) {
            return Applied.refused("not your companion");
        }
        Optional<CompanionLoop> loop = CompanionLoops.get(companion);
        if (loop.isEmpty()) {
            return Applied.refused("she has no brain here");
        }
        action.accept(loop.get());
        return Applied.done();
    }

    /** 技能命令的正文:{@code /名字 要求} 里名字是个开着的技能就展开,否则没有。聊天流只画主人打的那行。 */
    private static String skillBody(String echo) {
        if (echo == null || !echo.startsWith(PREFIX)) {
            return "";
        }
        String line = echo.substring(PREFIX.length()).strip();
        int space = line.indexOf(' ');
        String name = space < 0 ? line : line.substring(0, space);
        String args = space < 0 ? "" : line.substring(space + 1).strip();
        SkillRegistry registry = SkillRegistry.instance();
        Optional<SkillInfo> info = registry.get(name);
        return info.isPresent() && !registry.isDisabled(name) ? SkillInjection.body(info.get(), args) : "";
    }
}
