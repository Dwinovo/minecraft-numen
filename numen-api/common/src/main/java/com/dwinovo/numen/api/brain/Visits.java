package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentVenues;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道(QQ、Discord、弹幕桥……)上主人对她说话的一次"来访"。主人从渠道说一句话,由服务端大脑用主人的 key 去想,
 * 她的回话与要问主人的事回到那个渠道;这一轮做完他还不在游戏里,就回到收拾好的样子,不因来过一句话一直醒着。
 *
 * <p>来访期间主人算在场({@link OwnerPresence}):内脑不待命、征询不当场按"主人不在"拒绝。来访什么时候结束只有一个判据:
 * 她的内核没有在飞的回合、也没有哪一条马上要开出回合({@link CompanionLoop#settled})。渠道怎么显示、主人怎么点,
 * 是渠道插件自己的事——这里只给两个口:她说的话({@link Channel#said})与通用形式的征询({@link Channel#ask}),
 * 答复走 {@code ConsentDesk.reply},和游戏里同一个入口。
 *
 * <p>线程:服务端主线程。
 */
public final class Visits {

    /** 一个渠道那一头:收她的回话和征询。 */
    public interface Channel {

        /** 她说话了。 */
        void said(UUID companion, String text);

        /** 她要问主人一件事:渠道去问人。 */
        void ask(ConsentVenues.Offer offer);

        /** 那一条不用答了。 */
        void clear(long id, ConsentDesk.Withdrawal why);
    }

    private record Visit(UUID owner, Channel channel) {}

    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();

    /** 征询的场所:来访着的渠道。 */
    private static final ConsentVenues.Venue VENUE = new ConsentVenues.Venue() {
        @Override
        public void show(UUID owner, ConsentVenues.Offer offer) {
            Visit visit = VISITS.get(offer.companion());
            if (visit != null) {
                visit.channel.ask(offer);
            }
        }

        @Override
        public void clear(UUID owner, long id, ConsentDesk.Withdrawal why) {
            VISITS.values().stream().filter(v -> v.owner.equals(owner)).forEach(v -> v.channel.clear(id, why));
        }
    };

    static {
        ServerLifecycle.onStopped(VISITS::clear);
    }

    private Visits() {}

    /** 服务端起好时接入征询场所。 */
    static void start() {
        ConsentVenues.register(VENUE);
    }

    static void stop() {
        ConsentVenues.unregister(VENUE);
    }

    /** 渠道来人了:从这一刻起主人算在场,她的回话回这个渠道。同一只同伴上后来的渠道顶掉先来的。 */
    public static void begin(UUID companion, UUID owner, Channel channel) {
        VISITS.put(companion, new Visit(owner, channel));
    }

    /** 这次来访作废(话没送进去)。 */
    public static void end(UUID companion) {
        VISITS.remove(companion);
    }

    /** 这位主人名下有同伴正在被渠道来访着。 */
    static boolean visiting(UUID owner) {
        return VISITS.values().stream().anyMatch(v -> v.owner.equals(owner));
    }

    /** 她说了话:来访着就回给那个渠道。 */
    static void said(UUID companion, String text) {
        Visit visit = VISITS.get(companion);
        if (visit != null) {
            visit.channel.said(companion, text);
        }
    }

    /** 每个服务器刻一次:做完这一轮的来访结束,主人若不在游戏里、外部大脑也没连着,收拾一次。 */
    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Visit> entry : Map.copyOf(VISITS).entrySet()) {
            boolean settled = CompanionLoops.get(entry.getKey()).map(CompanionLoop::settled).orElse(true);
            if (settled && VISITS.remove(entry.getKey(), entry.getValue())) {
                OwnerPresence.settle(server, entry.getValue().owner);
            }
        }
    }
}
