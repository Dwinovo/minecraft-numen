package com.dwinovo.numen.api.brain;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一只同伴的各位主人那一头合成的一头(见 {@code docs/adr/runtime/0006-keepers-ownership-use-provenance.md} 第 12 条):
 * 每位主人还是他自己那个单人的 {@link CompanionLoops.Link},这里只管分给谁——循环里的动态推给表上装着客户端的每一位,
 * 她说出口的话回给说话的那一位。表与说话的人都是现问的:主人表变了、换了一位主人开口,这一头跟着变,不用重建大脑。
 */
final class KeeperLinks implements CompanionLoops.Link {

    private final Supplier<List<UUID>> keepers;
    private final Function<UUID, CompanionLoops.Link> one;
    private final Supplier<UUID> speaker;

    /**
     * @param keepers 她的主人表,创建者在前
     * @param one     一位主人自己的那一头
     * @param speaker 她在回谁的话({@link CompanionLoop#speaker});没有是 {@code null}
     */
    KeeperLinks(Supplier<List<UUID>> keepers, Function<UUID, CompanionLoops.Link> one, Supplier<UUID> speaker) {
        this.keepers = keepers;
        this.one = one;
        this.speaker = speaker;
    }

    /** 表上有一位装着可用的客户端。 */
    @Override
    public boolean hasClient() {
        return keepers.get().stream().anyMatch(keeper -> one.apply(keeper).hasClient());
    }

    /** 推给表上装着客户端的每一位。 */
    @Override
    public void send(UUID companion, List<Feed> events) {
        for (UUID keeper : keepers.get()) {
            CompanionLoops.Link end = one.apply(keeper);
            if (end.hasClient()) {
                end.send(companion, events);
            }
        }
    }

    /** 她的话回给说话的那一位;面板上的动态表上每一位都随 {@link #send} 看得到。 */
    @Override
    public void said(UUID companion, String said, Venue venue, String conversation) {
        UUID to = speaker.get();
        if (to != null && keepers.get().contains(to)) {
            one.apply(to).said(companion, said, venue, conversation);
        }
    }
}
