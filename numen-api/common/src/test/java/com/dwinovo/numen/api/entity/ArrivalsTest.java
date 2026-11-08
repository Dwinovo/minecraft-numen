package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.entity.Arrivals.Arrival;
import com.dwinovo.numen.api.entity.Arrivals.Kind;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接纳的编排:假的平台可以当场答,也可以先挂起、以后再答(异步的平台),身体用字符串顶替。 */
class ArrivalsTest {

    private final Arrivals<String> arrivals = new Arrivals<>();
    private final UUID rin = UUID.randomUUID();
    private final List<Arrival<String>> results = new ArrayList<>();
    private final List<String> joined = new ArrayList<>();

    /** 挂起的平台:答复先存下来,测试里想什么时候答就什么时候答。 */
    private Consumer<Component> pending;

    private void arrive(UUID id, String name, Arrivals.Admission admission, boolean wanted) {
        arrivals.arrive(id, name, admission, () -> wanted, () -> {
            joined.add(name);
            return name;
        }, results::add);
    }

    private final Arrivals.Admission immediateYes = verdict -> verdict.accept(null);
    private final Arrivals.Admission suspended = verdict -> pending = verdict;

    @Test
    void anAdmissionThePlatformGivesOnTheSpotJoinsOnTheSpot() {
        arrive(rin, "Rin", immediateYes, true);
        assertEquals(List.of("Rin"), joined);
        assertEquals(Kind.ARRIVED, results.get(0).kind());
        assertEquals("Rin", results.get(0).body());
        assertFalse(arrivals.isArriving(rin));
    }

    @Test
    void aRefusalOnTheSpotNeverJoinsAndCarriesTheReason() {
        Component why = Component.literal("banned");
        arrive(rin, "Rin", verdict -> verdict.accept(why), true);
        assertEquals(List.of(), joined);
        assertEquals(Kind.REFUSED, results.get(0).kind());
        assertEquals(why, results.get(0).refusal());
        assertFalse(arrivals.isArriving(rin));
    }

    @Test
    void anAdmissionThatComesLaterJoinsOnlyWhenItComes() {
        arrive(rin, "Rin", suspended, true);
        assertEquals(List.of(), results, "nothing happens until the platform answers");
        assertEquals(List.of(), joined);
        assertTrue(arrivals.isArriving(rin));
        pending.accept(null);
        assertEquals(List.of("Rin"), joined);
        assertEquals(Kind.ARRIVED, results.get(0).kind());
        assertFalse(arrivals.isArriving(rin));
    }

    @Test
    void aRefusalThatComesLaterEndsTheArrivalWithoutJoining() {
        arrive(rin, "Rin", suspended, true);
        pending.accept(Component.literal("whitelist"));
        assertEquals(List.of(), joined);
        assertEquals(Kind.REFUSED, results.get(0).kind());
        assertFalse(arrivals.isArriving(rin));
    }

    @Test
    void theSameBodyAskedForAgainWhileOnItsWayIsToldSoAndNotStartedTwice() {
        int[] asked = {0};
        arrive(rin, "Rin", verdict -> {
            asked[0]++;
            pending = verdict;
        }, true);
        arrive(rin, "Rin", verdict -> asked[0]++, true);
        assertEquals(1, asked[0], "the platform is asked once");
        assertEquals(Kind.UNDER_WAY, results.get(0).kind());
        pending.accept(null);
        assertEquals(List.of("Rin"), joined, "exactly one body joins");
        assertEquals(Kind.ARRIVED, results.get(1).kind());
    }

    @Test
    void aNameIsHeldFromTheMomentOfAcceptance() {
        arrive(rin, "Rin", suspended, true);
        assertEquals(rin, arrivals.arrivingNamed("rin").orElseThrow(), "names are matched without regard to case");
        assertTrue(arrivals.arrivingNamed("Mei").isEmpty());
        pending.accept(null);
        assertTrue(arrivals.arrivingNamed("Rin").isEmpty(), "released once she is in");
    }

    @Test
    void ifTheRegistryNoLongerWantsHerWhenTheAnswerComesShesNotJoined() {
        boolean[] wanted = {true};
        arrivals.arrive(rin, "Rin", suspended, () -> wanted[0], () -> {
            joined.add("Rin");
            return "Rin";
        }, results::add);
        wanted[0] = false;   // dismissed while the platform was thinking
        pending.accept(null);
        assertEquals(List.of(), joined);
        assertEquals(Kind.WITHDRAWN, results.get(0).kind());
        assertNull(results.get(0).body());
        assertFalse(arrivals.isArriving(rin));
    }

    @Test
    void aRefusalIsReportedAsARefusalEvenIfSheIsNoLongerWanted() {
        arrive(rin, "Rin", suspended, false);
        pending.accept(Component.literal("no"));
        assertEquals(Kind.REFUSED, results.get(0).kind());
    }

    @Test
    void aPlatformThatFailsToStartLeavesNoClaimBehind() {
        try {
            arrive(rin, "Rin", verdict -> {
                throw new IllegalStateException("scheduler is gone");
            }, true);
        } catch (IllegalStateException expected) {
            // the failure is the caller's to see
        }
        assertFalse(arrivals.isArriving(rin));
        assertTrue(arrivals.arrivingNamed("Rin").isEmpty());
    }
}
