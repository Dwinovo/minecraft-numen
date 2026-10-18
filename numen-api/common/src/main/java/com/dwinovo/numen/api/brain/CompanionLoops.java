package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.entity.CompanionSpeech;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

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
 * <p>每个大脑登记时带着主人那一头({@link Link})和主人的会话({@link Conversations}):循环里发生的事经 {@link FeedEncoder}
 * 按刻译成 {@link Feed} 推给主人,她说出口的话经 {@link Voice} 旁听、回话。
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

        /**
         * 主人没装客户端时,她们此刻各在干什么,显示在他的动作栏上(装了客户端的,头顶气泡与档案页读同一份状态)。
         * 只含正在干着具体活的。
         */
        void activities(Map<UUID, LoopStatus.Doing> doing);
    }

    private static final class Entry {
        final UUID owner;
        final CompanionLoop loop;
        final Link link;
        final FeedEncoder feed;
        /** 主人客户端的朗读此刻在放。 */
        volatile boolean voicePlaying;

        Entry(UUID owner, CompanionLoop loop, Link link) {
            this.owner = owner;
            this.loop = loop;
            this.link = link;
            this.feed = new FeedEncoder(loop, events -> {
                if (link.hasClient()) {
                    link.send(loop.companion(), events);
                }
            });
        }
    }

    private static final Map<UUID, Entry> LOOPS = new ConcurrentHashMap<>();

    /** 动作栏的字几刻刷新一次:原版的动作栏字约留三秒,一秒一刷既不断也不刷屏。 */
    private static final int ACTION_BAR_TICKS = 20;
    private static int ticks;

    static {
        ServerLifecycle.onStopped(CompanionLoops::dropAll);
    }

    private CompanionLoops() {}

    /** 这只同伴的大脑;没有是空。 */
    public static Optional<CompanionLoop> get(UUID companion) {
        Entry entry = LOOPS.get(companion);
        return entry == null ? Optional.empty() : Optional.of(entry.loop);
    }

    /** 登记一只同伴的大脑;她原来的先收掉(它在飞的回合作废,不再写她的日志)。 */
    public static void install(UUID owner, CompanionLoop loop, Link link, Conversations rooms) {
        Entry entry = new Entry(owner, loop, link);
        Voice.attach(loop, rooms, link);
        Entry old = LOOPS.put(loop.companion(), entry);
        if (old != null) {
            old.loop.dispose();
        }
    }

    /** 每个服务器刻一次:各大脑播报停牌变化、问熟度,攒下的事件推给主人,身体据此决定是不是在说话。 */
    public static void tick() {
        for (Entry entry : LOOPS.values()) {
            entry.loop.tick();
            entry.feed.flush();
            Phase phase = entry.loop.status().phase();
            CompanionSpeech.setSpeaking(entry.loop.companion(),
                    phase == Phase.MODEL || phase == Phase.TOOLS || entry.voicePlaying);
        }
        if (++ticks % ACTION_BAR_TICKS == 0) {
            showActivities();
        }
    }

    /** 没装客户端的主人:他名下各只正在干的活合成一行,放在他的动作栏上(读的是 {@link LoopStatus#activity} 那一处)。 */
    private static void showActivities() {
        Map<UUID, Map<UUID, LoopStatus.Doing>> byOwner = new LinkedHashMap<>();
        Map<UUID, Link> links = new LinkedHashMap<>();
        for (Entry entry : LOOPS.values()) {
            LoopStatus.Doing doing = entry.link.hasClient() ? null : entry.loop.status().activity();
            if (doing != null) {
                byOwner.computeIfAbsent(entry.owner, owner -> new LinkedHashMap<>()).put(entry.loop.companion(), doing);
                links.putIfAbsent(entry.owner, entry.link);
            }
        }
        byOwner.forEach((owner, doing) -> links.get(owner).activities(doing));
    }

    /** 主人的客户端报到(或重连)了:把他名下各大脑此刻的样子补给他。 */
    public static void sendSnapshots(UUID owner, Link link) {
        for (Entry entry : LOOPS.values()) {
            if (entry.owner.equals(owner)) {
                link.send(entry.loop.companion(), entry.feed.snapshot());
            }
        }
    }

    /** 这位主人的某一项库条目改了名(id 就是名字):名下绑着它的大脑改指新的 id。 */
    public static void rebind(UUID owner, Slot slot, String from, String to) {
        for (Entry entry : LOOPS.values()) {
            if (entry.owner.equals(owner) && from.equals(entry.loop.bound(slot))) {
                entry.loop.bind(slot, to);
            }
        }
    }

    /** 她的记忆被清空了:推给主人的面板,把她的记录作废。 */
    static void wiped(UUID companion) {
        Entry entry = LOOPS.get(companion);
        if (entry != null) {
            entry.feed.wiped();
        }
    }

    /** 绑着这一条库条目的在场同伴,连同各自的主人(内置条目是所有人共用的,谁名下的都算)。 */
    static Map<UUID, UUID> boundTo(Slot slot, String entryId) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        LOOPS.values().stream().filter(entry -> entryId.equals(entry.loop.bound(slot)))
                .forEach(entry -> out.put(entry.loop.companion(), entry.owner));
        return out;
    }

    /** 内置的库条目改了名:所有人名下绑着它的大脑都改指新的 id。 */
    public static void rebindAll(Slot slot, String from, String to) {
        for (Entry entry : LOOPS.values()) {
            if (from.equals(entry.loop.bound(slot))) {
                entry.loop.bind(slot, to);
            }
        }
    }

    /** 主人客户端的朗读开始、停止放音。 */
    public static void voicePlaying(UUID companion, boolean playing) {
        Entry entry = LOOPS.get(companion);
        if (entry != null) {
            entry.voicePlaying = playing;
        }
    }

    /** 这位主人的玩家下线了:他的客户端不再算在场,他的朗读不再在放。 */
    static void clientLeft(UUID owner) {
        Presence.left(owner);
        ChatRoutes.forget(owner);
        OwnerInputs.dropMailbox(owner);
        for (Entry entry : LOOPS.values()) {
            if (entry.owner.equals(owner)) {
                entry.voicePlaying = false;
            }
        }
    }

    /** 这位主人不在了({@link OwnerPresence}):他名下各大脑作废在飞的回应、放弃未决调用,待命。 */
    static void ownerGone(UUID owner) {
        for (Entry entry : LOOPS.values()) {
            if (entry.owner.equals(owner)) {
                entry.loop.ownerLeft();
            }
        }
    }

    /**
     * 这只同伴不存在了(遣散):大脑收掉,她的家整个删——删的活排在她那条车道的最后,在它前面还没写下去的写先落完,
     * 之后不会有东西把已删的文件写回来。
     */
    public static void dismissed(MinecraftServer server, UUID companion) {
        Entry entry = LOOPS.remove(companion);
        if (entry != null) {
            entry.loop.dispose();
        }
        CompanionSpeech.setSpeaking(companion, false);
        Homes homes = Homes.of(server);
        Home home = homes.companion(companion);
        home.delete();
        homes.forgetCompanion(companion);
    }

    /** 这只同伴的大脑放手:在飞的回合作废,不再写她的日志;她的家原样留着,下一次 {@code ensure} 从那里重新读起。 */
    static void release(UUID companion) {
        Entry entry = LOOPS.remove(companion);
        if (entry != null) {
            entry.loop.dispose();
        }
    }

    private static void dropAll() {
        LOOPS.values().forEach(entry -> entry.loop.dispose());
        LOOPS.clear();
    }
}
