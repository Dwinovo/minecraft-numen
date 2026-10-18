package com.dwinovo.numen.api.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModPermissionsTest {

    /** 一个 op 等级为 {@code level} 的来源:有没有某一级。 */
    private static java.util.function.IntPredicate op(int level) {
        return required -> level >= required;
    }

    @Test
    void theSingleplayerOwnerIsAnAdminWhateverHisOpLevel() {
        assertTrue(ModPermissions.isAdmin(true, op(0)));
    }

    @Test
    void anOpIsAnAdminOnlyAtTheTopLevel() {
        assertTrue(ModPermissions.isAdmin(false, op(4)));
        assertFalse(ModPermissions.isAdmin(false, op(3)));
        assertFalse(ModPermissions.isAdmin(false, op(0)));
    }

    @Test
    void gameMasterNeedsOpLevelTwo() {
        assertTrue(ModPermissions.isGameMaster(op(2)));
        assertFalse(ModPermissions.isGameMaster(op(1)));
    }
}
