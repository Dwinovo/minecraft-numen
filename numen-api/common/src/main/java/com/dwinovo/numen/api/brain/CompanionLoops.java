package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.CompanionSpeech;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这个世界里跑着的服务端大脑,按同伴的 UUID。谁在服务端要找某只同伴的大脑(事件送进她的收件箱、死亡与复活、主人登出)
 * 都问这一处;没有登记的同伴没有服务端大脑,调用方照旧走原来的路。
 *
 * <p>每个大脑登记时带着主人那一头({@link Link};她不止一位主人时是各位那一头的组合,{@link KeeperLinks})和各位主人的会话
 * ({@link Conversations}):循环里发生的事经 {@link FeedEncoder} 按刻译成 {@link Feed} 推给主人,她说出口的话经 {@link Voice} 旁听、回话。
 *
 * <p>线程:服务端主线程。关服时全部收掉(世界作用域的状态,见 {@link ServerLifecycle})。
 */
public final class CompanionLoops {

    /** 主人那一头:他的客户端在不在、怎么把东西推给他、她的话怎么交到他手里。 */
    public interface Link {

        /** 主人在线,而且装着可用的客户端。 */
        boolean hasClient();

        /** 推给主人的客户端。 */
        void send(UUID companion, List<Feed> events);

        /**
         * 她说出口的一句话交给主人,按他是在哪儿问的({@link Venue})接:该回在面板的,装了客户端的随 {@link #send} 的
         * {@link Feed.Replied} 看到,这里什么也不做;其余回成一行聊天。
         *
         * @param conversation 她此刻所在会话的名字;会话已经解散了是 {@code null}
         */
        void said(UUID companion, String said, Venue venue, String conversation);
    }

    private static final class Entry {
        final UUID owner;
        final CompanionLoop loop;
        final Link link;
        final FeedEncoder feed;
        /** 她的 run 开收,攒着在 {@link #tick} 里派给插件。 */
        final BrainEventBridge bridge;
        /** 哪几位主人的客户端的朗读此刻在放。 */
        final java.util.Set<UUID> voicePlaying = ConcurrentHashMap.newKeySet();

        Entry(UUID owner, CompanionLoop loop, Link link) {
            this.owner = owner;
            this.loop = loop;
            this.link = link;
            this.feed = new FeedEncoder(loop, events -> {
                if (link.hasClient()) {
                    link.send(loop.companion(), events);
                }
            });
            this.bridge = new BrainEventBridge(loop);
        }

        /** 大脑退场:在飞的回合作废,它因此收尾的 run 先派给插件,开了的 run 都有收。 */
        void retire() {
            loop.dispose();
            bridge.flush();
        }
    }

    private static final Map<UUID, Entry> LOOPS = new ConcurrentHashMap<>();

    /** 动作栏的字几刻刷新一次:原版的动作栏字约留三秒,一秒一刷既不断也不刷屏。 */
    private static final int ACTION_BAR_TICKS = 20;
    private static int ticks;

    static {
        ServerLifecycle.onStopped(CompanionLoops::dropAll);
        ServerLifecycle.onStopped(com.dwinovo.numen.api.agent.trace.TraceRecorders::forgetAll);
    }

    private CompanionLoops() {}

    /** 这只同伴的大脑;没有是空。 */
    public static Optional<CompanionLoop> get(UUID companion) {
        Entry entry = LOOPS.get(companion);
        return entry == null ? Optional.empty() : Optional.of(entry.loop);
    }

    /**
     * 登记一只同伴的大脑;她原来的先收掉(它在飞的回合作废,不再写她的日志)。
     *
     * @param owner   她的创建者
     * @param link    她的主人那一头
     * @param roomsOf 一位主人的会话表
     */
    public static void install(UUID owner, CompanionLoop loop, Link link,
                               java.util.function.Function<UUID, Conversations> roomsOf) {
        Entry entry = new Entry(owner, loop, link);
        Voice.attach(loop, roomsOf, link);
        Entry old = LOOPS.put(loop.companion(), entry);
        if (old != null) {
            old.retire();
        }
    }

    /** 每个服务器刻一次:各大脑播报停牌变化、问熟度,攒下的事件推给主人、攒下的 run 开收派给插件,身体据此决定是不是在说话。 */
    public static void tick(MinecraftServer server) {
        for (Entry entry : LOOPS.values()) {
            entry.loop.tick();
            entry.feed.flush();
            entry.bridge.flush();
            Phase phase = entry.loop.phase();
            CompanionSpeech.setSpeaking(entry.loop.companion(),
                    phase == Phase.MODEL || phase == Phase.TOOLS || !entry.voicePlaying.isEmpty());
        }
        if (++ticks % ACTION_BAR_TICKS == 0) {
            showActivities(server);
        }
    }

    /**
     * 没装客户端的主人:他用得了的各只正在干的活合成一行,放在他的动作栏上(读的是 {@link LoopStatus#activity} 那一处;
     * 装了客户端的,头顶气泡与档案页读同一份状态)。动作栏是按人的,所以按在线的人算,不经每只同伴的那一头。
     */
    private static void showActivities(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer || Presence.hasClient(player.getUUID())) {
                continue;
            }
            Map<UUID, LoopStatus.Doing> doing = new LinkedHashMap<>();
            for (Map.Entry<UUID, CompanionRegistry.Entry> hers : CompanionRegistry.get(server).usableBy(player.getUUID())) {
                Entry entry = LOOPS.get(hers.getKey());
                LoopStatus.Doing now = entry == null ? null : entry.loop.activity();
                if (now != null) {
                    doing.put(hers.getKey(), now);
                }
            }
            if (!doing.isEmpty()) {
                Replies.activities(player, doing);
            }
        }
    }

    /** 这只同伴的大脑此刻的样子,补给这一头(他的客户端报到或重连了、她刚分享给他)。她没有大脑就什么也不发。 */
    public static void sendSnapshot(UUID companion, Link link) {
        Entry entry = LOOPS.get(companion);
        if (entry != null) {
            link.send(companion, entry.feed.snapshot());
        }
    }

    /**
     * 这个人"我的"里的某一条改了名(id 就是名字):他绑的这一条——不管绑在谁创建的同伴身上——改指新的 id。
     * 别人绑的同名条目是别人库里的,不动。
     */
    public static void rebind(UUID by, Slot slot, EntryId from, EntryId to) {
        Bound was = new Bound(by, from);
        for (Entry entry : LOOPS.values()) {
            entry.loop.repoint(slot, was, to);
        }
    }

    /** 她的记忆被清空了:推给主人的面板,把她的记录作废。 */
    static void wiped(UUID companion) {
        Entry entry = LOOPS.get(companion);
        if (entry != null) {
            entry.feed.wiped();
        }
    }

    /** 这位主人客户端的朗读开始、停止放音。 */
    public static void voicePlaying(UUID companion, UUID owner, boolean playing) {
        Entry entry = LOOPS.get(companion);
        if (entry == null) {
            return;
        }
        if (playing) {
            entry.voicePlaying.add(owner);
        } else {
            entry.voicePlaying.remove(owner);
        }
    }

    /** 这位主人的玩家下线了:他的客户端不再算在场,他的朗读不再在放。 */
    static void clientLeft(UUID owner) {
        Presence.left(owner);
        ChatRoutes.forget(owner);
        OwnerInputs.dropMailbox(owner);
        for (Entry entry : LOOPS.values()) {
            entry.voicePlaying.remove(owner);
        }
    }

    /** 她的主人表上已无人在场({@link OwnerPresence}):她的大脑作废在飞的回应、放弃未决调用,待命。 */
    static void keepersGone(UUID companion) {
        Entry entry = LOOPS.get(companion);
        if (entry != null) {
            entry.loop.ownerLeft();
        }
    }

    /**
     * 这只同伴不存在了(遣散):大脑收掉,她的家整个删——删的活排在她那条车道的最后,在它前面还没写下去的写先落完,
     * 之后不会有东西把已删的文件写回来。
     */
    public static void dismissed(MinecraftServer server, UUID companion) {
        Entry entry = LOOPS.remove(companion);
        if (entry != null) {
            entry.retire();
        }
        CompanionSpeech.setSpeaking(companion, false);
        Homes homes = Homes.of(server);
        com.dwinovo.numen.api.agent.trace.TraceRecorders.forget(companion);
        Home home = homes.companion(companion);
        home.delete();
        homes.forgetCompanion(companion);
    }

    /** 这只同伴的大脑放手:在飞的回合作废,不再写她的日志;她的家原样留着,下一次 {@code ensure} 从那里重新读起。 */
    static void release(UUID companion) {
        Entry entry = LOOPS.remove(companion);
        if (entry != null) {
            entry.retire();
        }
    }

    private static void dropAll() {
        LOOPS.values().forEach(entry -> entry.loop.dispose());
        LOOPS.clear();
    }
}
