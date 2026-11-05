package com.dwinovo.numen.api.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 规则表一刻里变了很多次(挖一格就命中一次):攒到合并的那一刻,每位主人只推一包;不到那一刻不推、攒着的也不丢。 */
class PermissionFeedTest {

    @Test
    void manyChangesBeforeTheWindowReachThePanelAsOnePush() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            PermissionFeed.changed(owner);
        }
        assertEquals(List.of(), PermissionFeed.due(PermissionFeed.EVERY_TICKS * 7 + 3), "pushed before the window");
        // 同一个 JVM 里别的用例改过的存档也会叫一声:只数这位主人
        assertEquals(1, PermissionFeed.due(PermissionFeed.EVERY_TICKS * 8).stream().filter(owner::equals).count());
        assertFalse(PermissionFeed.due(PermissionFeed.EVERY_TICKS * 9).contains(owner), "pushed twice for one burst");
    }
}
