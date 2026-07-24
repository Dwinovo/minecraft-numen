package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatToolsTest {

    @Test
    void normalizesRuntimeIdsWithoutChangingTheirOrder() {
        assertEquals(List.of(184, 207, 215),
                CombatTools.normalizeEntityIds(List.of(184, 207, 184, 215)));
    }

    @Test
    void rejectsAnEmptyAuthorizedSet() {
        assertThrows(IllegalArgumentException.class,
                () -> CombatTools.normalizeEntityIds(List.of()));
    }

    @Test
    void rejectsMoreThanTwentyDistinctTargets() {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= 21; id++) ids.add(id);
        assertThrows(IllegalArgumentException.class,
                () -> CombatTools.normalizeEntityIds(ids));
    }
}
