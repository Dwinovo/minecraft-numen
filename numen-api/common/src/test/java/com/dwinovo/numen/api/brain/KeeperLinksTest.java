package com.dwinovo.numen.api.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 各位主人那一头的组合({@link KeeperLinks}):动态推给表上装着客户端的每一位,她的话只回给说话的那一位,表现问现答。 */
class KeeperLinksTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    /** 一位主人那一头:记下推给他的和回给他的。 */
    private static final class End implements CompanionLoops.Link {
        boolean client;
        final List<Feed> sent = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        End(boolean client) {
            this.client = client;
        }

        @Override
        public boolean hasClient() {
            return client;
        }

        @Override
        public void send(UUID companion, List<Feed> events) {
            sent.addAll(events);
        }

        @Override
        public void said(UUID companion, String words, Venue venue, String conversation) {
            said.add(venue + ":" + words);
        }
    }

    private final List<UUID> keepers = new ArrayList<>(List.of(CREATOR, FRIEND));
    private final Map<UUID, End> ends = new HashMap<>(Map.of(CREATOR, new End(true), FRIEND, new End(true)));
    private UUID speaker = CREATOR;
    private final KeeperLinks links = new KeeperLinks(() -> keepers, ends::get, () -> speaker);

    @Test
    void theFeedGoesToEveryKeeperWithAClient() {
        ends.get(FRIEND).client = false;

        links.send(HER, List.of(new Feed.Halted()));

        assertEquals(1, ends.get(CREATOR).sent.size());
        assertTrue(ends.get(FRIEND).sent.isEmpty(), "没装客户端的那一位不该收到我们的包");
        assertTrue(links.hasClient(), "有一位装着客户端就算有");
        ends.get(CREATOR).client = false;
        assertFalse(links.hasClient());
    }

    @Test
    void herWordsGoBackOnlyToTheKeeperWhoSpoke() {
        speaker = FRIEND;

        links.said(HER, "好的", Venue.WHISPER, null);

        assertEquals(List.of("WHISPER:好的"), ends.get(FRIEND).said);
        assertTrue(ends.get(CREATOR).said.isEmpty(), "不是他问的,不回他");
    }

    @Test
    void theTableIsAskedEveryTimeSoAChangeNeedsNoRebuild() {
        UUID late = UUID.randomUUID();
        ends.put(late, new End(true));
        keepers.add(late);
        links.send(HER, List.of(new Feed.Halted()));
        assertEquals(1, ends.get(late).sent.size(), "新加进表的那一位当场就收得到");

        keepers.remove(FRIEND);
        speaker = FRIEND;
        links.send(HER, List.of(new Feed.Halted()));
        links.said(HER, "在吗", Venue.PANEL, null);
        assertEquals(1, ends.get(FRIEND).sent.size(), "移出表的那一位不再收到");
        assertTrue(ends.get(FRIEND).said.isEmpty(), "也不再有话回给他");
    }
}
