package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;
import java.util.List;

public final class CombatWeaponSelectorTest {
    @Test
    void choosesReadyRangedSpearAndCloseRangeWeapons() {
        CombatWeaponSelector.Loadout loadout = CombatWeaponSelector.choose(List.of(
            new CombatWeaponSelector.Candidate(2, CombatWeaponSelector.Kind.BOW, 0.0, true, false),
            new CombatWeaponSelector.Candidate(5, CombatWeaponSelector.Kind.CROSSBOW, 0.0, true, true)
        ));

        assertEquals(
            5,
            loadout.ranged().slot(),
            "a charged crossbow must be ready immediately and win over a bow that still needs drawing"
        );

        loadout = CombatWeaponSelector.choose(List.of(
            new CombatWeaponSelector.Candidate(1, CombatWeaponSelector.Kind.SPEAR, 5.0, true, false),
            new CombatWeaponSelector.Candidate(3, CombatWeaponSelector.Kind.SPEAR, 7.0, true, false),
            new CombatWeaponSelector.Candidate(4, CombatWeaponSelector.Kind.MELEE, 6.0, true, false),
            new CombatWeaponSelector.Candidate(6, CombatWeaponSelector.Kind.MELEE, 8.0, true, false)
        ));

        assertEquals(3, loadout.spear().slot(), "the strongest spear must own the spear distance band");
        assertEquals(6, loadout.melee().slot(), "the strongest ordinary melee weapon, including a mace, must own close range");

        loadout = CombatWeaponSelector.choose(List.of(
            new CombatWeaponSelector.Candidate(3, CombatWeaponSelector.Kind.SPEAR, 7.0, true, false)
        ));
        assertEquals(
            3,
            loadout.meleeOrSpear().slot(),
            "when no ordinary melee weapon exists, close range must fall back to a normal spear swing instead of an empty hand"
        );
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
