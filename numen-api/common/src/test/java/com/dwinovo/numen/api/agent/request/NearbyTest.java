package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.agent.request.Nearby.Candidate;
import com.dwinovo.numen.api.agent.request.Nearby.Rank;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 附近栏怎么排、怎么截、怎么写:逐个列的先后、条数上限、其余的计数,以及什么都没有时一个字都不写。 */
class NearbyTest {

    private static Candidate mob(String kind, int n, String health, double distance, Rank rank, boolean targetsHer) {
        return new Candidate(kind + "@" + String.format("%06x", n), kind, health, distance, "north", rank, targetsHer);
    }

    private static Candidate other(String kind, double distance) {
        return new Candidate(kind + "@aaaaaa", kind, "", distance, "south", Rank.OTHER, false);
    }

    @Test
    void relatedThenHostileThenPlayersEachByDistance() {
        Nearby nearby = Nearby.compose(List.of(
                new Candidate("Steve", "player", "20/20", 2, "east", Rank.PLAYER, false),
                mob("zombie", 1, "20/20", 3, Rank.HOSTILE, false),
                mob("wolf", 2, "8/8", 9, Rank.RELATED, false),
                mob("skeleton", 3, "20/20", 8, Rank.HOSTILE, false),
                mob("zombie", 4, "20/20", 12, Rank.RELATED, true)));

        assertEquals(List.of("wolf@000002", "zombie@000004", "zombie@000001", "skeleton@000003", "Steve"),
                nearby.lines().stream().map(Nearby.Line::id).toList());
    }

    @Test
    void whatIsNotListedIsCountedByKindMostFirst() {
        Nearby nearby = Nearby.compose(List.of(other("cow", 5), other("item", 4), other("cow", 6), other("cow", 7),
                other("item", 8), other("sheep", 3)));

        assertTrue(nearby.lines().isEmpty());
        assertEquals("<nearby radius=\"16\">\ncow×3, item×2, sheep×1\n</nearby>", nearby.xml());
    }

    @Test
    void atMostTwelveAreListedAndTheRestJoinTheCounts() {
        List<Candidate> crowd = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            crowd.add(mob("zombie", i, "20/20", 3 + i, Rank.HOSTILE, false));
        }
        crowd.add(other("cow", 5));

        Nearby nearby = Nearby.compose(crowd);

        assertEquals(Nearby.MAX_LINES, nearby.lines().size());
        assertEquals("zombie@00000b", nearby.lines().get(11).id(), "近的先列,远的两只退进计数");
        assertEquals(List.of(new Nearby.Count("zombie", 2), new Nearby.Count("cow", 1)), nearby.counts());
    }

    @Test
    void manyKindsAreCappedWithAnOtherTotal() {
        List<Candidate> farm = new ArrayList<>();
        for (int i = 0; i < Nearby.MAX_KINDS + 3; i++) {
            farm.add(other("kind" + (char) ('a' + i), 5));
        }

        Nearby nearby = Nearby.compose(farm);

        assertEquals(Nearby.MAX_KINDS + 1, nearby.counts().size());
        assertEquals(new Nearby.Count("other", 3), nearby.counts().get(Nearby.MAX_KINDS));
    }

    @Test
    void aLineSaysWhoHowHurtHowFarWhichWayAndWhoIsAfterHer() {
        Nearby nearby = Nearby.compose(List.of(
                mob("zombie", 0x3f9a1c, "20/20", 4.6, Rank.RELATED, true),
                new Candidate("Steve", "player", "", 0, "here", Rank.PLAYER, false)));

        assertEquals("<nearby radius=\"16\">\n"
                + "zombie@3f9a1c 20/20 HP, 5 blocks north, targeting you\n"
                + "Steve, on you\n"
                + "</nearby>", nearby.xml());
    }

    @Test
    void nothingAroundWritesNothing() {
        assertEquals("", Nearby.compose(List.of()).xml());
        assertEquals("", Nearby.NONE.xml());
    }
}
