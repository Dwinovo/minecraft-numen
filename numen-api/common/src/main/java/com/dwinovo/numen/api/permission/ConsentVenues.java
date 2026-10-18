package com.dwinovo.numen.api.permission;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 游戏之外能问主人的地方。主人在游戏里时征询只在游戏里问(答复框);主人不在游戏里、但外部大脑连着或在渠道上说话时,
 * 征询交给这些"场所":外部大脑的客户端(MCP elicitation,问的是坐在电脑前的人,不是驾驶席上的模型)、渠道。
 * 场所怎么显示、主人怎么答是它们自己的事;答复回来都走 {@link ConsentDesk#reply},和游戏里同一个入口。
 *
 * <p>没有场所愿意问(外部大脑的客户端不支持 elicitation、没有渠道在说话),征询就一直挂到主人进游戏答,到期按拒绝——
 * 这里不提供任何让模型自己答的口。
 *
 * <p>线程:服务端主线程。
 */
public final class ConsentVenues {

    /**
     * 给场所看的一条征询,通用形式:号、哪只同伴、要做的事。要做的事是清单的文字({@link ConsentItem#listingText}),
     * {@code remembers} 是选"允许并记住"会写进主人 allow 表的规则行;答复是 allow、remember、deny 三种,拒绝可带一句话。
     */
    public record Offer(long id, UUID companion, String companionName, String doing, List<String> remembers) {

        public Offer {
            remembers = List.copyOf(remembers);
        }

        public static Offer of(String companionName, ConsentRequest request) {
            return new Offer(request.id(), request.companion(), companionName,
                    ConsentItem.listingText(request.items()), ConsentItem.rememberedRows(request.items()));
        }
    }

    /** 一个场所。 */
    public interface Venue {

        /** 问这位主人这一条;这个场所问不了(没有能答的人)就什么也不做。 */
        void show(UUID owner, Offer offer);

        /** 这一条不用答了(主人在别处答了、到期、任务收场):撤掉。没问过这一条的场所什么也不做。 */
        void clear(UUID owner, long id, ConsentDesk.Withdrawal why);
    }

    private static final List<Venue> VENUES = new CopyOnWriteArrayList<>();

    private ConsentVenues() {}

    public static void register(Venue venue) {
        VENUES.add(venue);
    }

    public static void unregister(Venue venue) {
        VENUES.remove(venue);
    }

    static void show(UUID owner, Offer offer) {
        VENUES.forEach(venue -> venue.show(owner, offer));
    }

    static void clear(UUID owner, long id, ConsentDesk.Withdrawal why) {
        VENUES.forEach(venue -> venue.clear(owner, id, why));
    }
}
