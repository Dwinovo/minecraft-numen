package com.dwinovo.numen.api.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "她的主人在不在场"的判据({@link OwnerPresence#anyPresent}):表上任一位在场就算;外部大脑那条腿只有创建者有。 */
class OwnerPresenceTest {

    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();
    private static final List<UUID> KEEPERS = List.of(CREATOR, FRIEND);

    private static boolean present(Set<UUID> inGame, Set<UUID> brain, Set<UUID> visiting) {
        return OwnerPresence.anyPresent(KEEPERS, inGame::contains, brain::contains, visiting::contains);
    }

    @Test
    void anyKeeperInTheGameKeepsHerOwnersPresent() {
        assertTrue(present(Set.of(CREATOR), Set.of(), Set.of()));
        assertTrue(present(Set.of(FRIEND), Set.of(), Set.of()), "分享给的人在游戏里,创建者不在也算在场");
        assertFalse(present(Set.of(), Set.of(), Set.of()), "表上没有一位在场");
        assertFalse(present(Set.of(UUID.randomUUID()), Set.of(), Set.of()), "不在表上的人在不在都不算");
    }

    @Test
    void anExternalBrainCountsOnlyForTheCreator() {
        assertTrue(present(Set.of(), Set.of(CREATOR), Set.of()));
        assertFalse(present(Set.of(), Set.of(FRIEND), Set.of()), "被分享的人的外部大脑驾驶不了她,撑不起在场");
    }

    @Test
    void aChannelVisitCountsForAnyKeeper() {
        assertTrue(present(Set.of(), Set.of(), Set.of(CREATOR)));
        assertTrue(present(Set.of(), Set.of(), Set.of(FRIEND)));
    }

    @Test
    void aCompanionWithNoKeepersHasNobodyPresent() {
        assertFalse(OwnerPresence.anyPresent(List.of(), who -> true, who -> true, who -> true));
    }
}
