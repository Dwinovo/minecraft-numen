package com.dwinovo.numen.api.witness;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 账本:动作之前把登记着的回答者挨个问一遍,动作之后把它们各自说的变化按登记先后接起来;不归它管的不再问,出错的只丢它自己那一条。
 */
class LedgerTest {

    /** 登记几个回答者跑一段,不论成败都撤掉:这张表是全进程共用的。 */
    private static void with(List<Witness> witnesses, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            for (Witness witness : witnesses) {
                handles.add(Questions.WITNESS.register(witness));
            }
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    @Test
    void eachWitnessRecordsBeforeAndSaysWhatChangedAfter() {
        AtomicInteger charge = new AtomicInteger(5);
        Witness wand = (her, focus) -> {
            int was = charge.get();
            return now -> charge.get() == was ? List.of() : List.of("wand charge: " + was + " -> " + charge.get());
        };
        Witness quiet = (her, focus) -> now -> List.of();
        Witness second = (her, focus) -> now -> List.of("second speaks");
        with(List.of(wand, quiet, second), () -> {
            Ledger ledger = Ledger.open(null, Focus.SELF);
            charge.set(3);
            assertEquals(List.of("wand charge: 5 -> 3", "second speaks"), ledger.close());
        });
    }

    @Test
    void aWitnessThatHandsBackNothingIsNotAskedAgain() {
        Witness notMine = (her, focus) -> null;
        Witness mine = (her, focus) -> now -> List.of("mine");
        with(List.of(notMine, mine), () -> assertEquals(List.of("mine"), Ledger.open(null, Focus.SELF).close()));
    }

    @Test
    void aWitnessThatThrowsLosesOnlyItsOwnLine() {
        Witness breaksBefore = (her, focus) -> {
            throw new IllegalStateException("before");
        };
        Witness breaksAfter = (her, focus) -> now -> {
            throw new IllegalStateException("after");
        };
        Witness sound = (her, focus) -> now -> List.of("still here");
        with(List.of(breaksBefore, breaksAfter, sound),
                () -> assertEquals(List.of("still here"), Ledger.open(null, Focus.SELF).close()));
    }

    @Test
    void closingTwiceGivesTheSameAccount() {
        AtomicInteger asked = new AtomicInteger();
        Witness counting = (her, focus) -> now -> List.of("asked " + asked.incrementAndGet());
        with(List.of(counting), () -> {
            Ledger ledger = Ledger.open(null, Focus.SELF);
            List<String> first = ledger.close();
            assertEquals(List.of("asked 1"), first);
            assertSame(first, ledger.close());
        });
    }
}
