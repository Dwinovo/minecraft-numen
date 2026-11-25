package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.permission.ConsentDesk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 征询在主人客户端上的去处。服务端只把征询推给主人的客户端;答复框是其中一个去处,外部大脑的客户端(MCP elicitation,问的是坐在电脑前的人,
 * 不是驾驶席上的模型)与渠道是另外的"场所"。场所怎么显示、主人怎么答是它们自己的事;答复回来都经 {@link #answer} 发回服务端,和答复框同一个入口。
 *
 * <p>没有场所愿意问(外部大脑的客户端不支持 elicitation、没有渠道在说话),征询就一直挂在答复框里,到期服务端按拒绝——
 * 这里不提供任何让模型自己答的口。外部大脑撞上"问"得到的是同一条征询,答复框与场所里的人谁先答谁算。
 *
 * <p>线程:客户端主线程({@link #show}、{@link #clear});{@link #pending} 任何线程。
 */
public final class ConsentVenues {

    /**
     * 给场所看的一条征询,通用形式:号、哪只同伴、要做的事。{@code remembers} 是选"以后都允许"会记进主人名单的几项;
     * 答复是 allow、remember、deny 三种,允许与拒绝都可带一句话。
     */
    public record Offer(long id, UUID companion, String companionName, String doing, List<String> remembers) {

        public Offer {
            remembers = List.copyOf(remembers);
        }
    }

    /** 一个场所。 */
    public interface Venue {

        /** 问这位主人这一条;这个场所问不了(没有能答的人)就什么也不做。 */
        void show(Offer offer);

        /** 这一条不用答了(主人在别处答了、到期、任务收场):撤掉。没问过这一条的场所什么也不做。 */
        void clear(long id, ConsentDesk.Withdrawal why);
    }

    /** 把答复发回服务端(和答复框同一个包)。 */
    public interface Answers {

        void send(UUID companion, long id, com.dwinovo.numen.api.permission.ConsentAnswer.Decision decision, String note);
    }

    private final List<Venue> venues = new CopyOnWriteArrayList<>();
    /** 每只同伴此刻挂着的那一条。 */
    private final Map<UUID, Offer> pending = new ConcurrentHashMap<>();
    private final Answers answers;

    public ConsentVenues(Answers answers) {
        this.answers = answers;
    }

    public void register(Venue venue) {
        venues.add(venue);
    }

    public void unregister(Venue venue) {
        venues.remove(venue);
    }

    /** 服务端推来一条征询:同一只同伴原来那条被它顶替。 */
    public void show(Offer offer) {
        Offer old = pending.put(offer.companion(), offer);
        if (old != null) {
            venues.forEach(venue -> venue.clear(old.id(), ConsentDesk.Withdrawal.SUPERSEDED));
        }
        venues.forEach(venue -> venue.show(offer));
    }

    /** 服务端撤回了这只同伴挂着的那一条;{@code why} 为空是主人自己答的。 */
    public void clear(UUID companion, ConsentDesk.Withdrawal why) {
        Offer gone = pending.remove(companion);
        if (gone != null) {
            venues.forEach(venue -> venue.clear(gone.id(), why));
        }
    }

    /** 这只同伴此刻挂着的那一条;没有是 {@code null}。 */
    public Offer pending(UUID companion) {
        return pending.get(companion);
    }

    /**
     * 场所里的人答了:挂着的就是这一条才发回服务端,不是(已经答过、到期、被顶替)就是没有可答的。
     *
     * @return 发出去了
     */
    public boolean answer(UUID companion, long id, com.dwinovo.numen.api.permission.ConsentAnswer.Decision decision,
                          String note) {
        Offer offer = pending.get(companion);
        if (offer == null || offer.id() != id) {
            return false;
        }
        answers.send(companion, id, decision, note);
        clear(companion, null);
        return true;
    }

    /** 断线:挂着的都不算数了。 */
    public void clearAll() {
        for (UUID companion : List.copyOf(pending.keySet())) {
            clear(companion, ConsentDesk.Withdrawal.SUPERSEDED);
        }
    }
}
