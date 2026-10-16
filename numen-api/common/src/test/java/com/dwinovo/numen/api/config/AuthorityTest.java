package com.dwinovo.numen.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityTest {

    @Test
    void theSingleplayerOwnerIsAnAdminWhateverHisOpLevel() {
        assertTrue(Authority.isAdmin(true, 0));
    }

    @Test
    void anOpIsAnAdminOnlyAtTheTopLevel() {
        assertTrue(Authority.isAdmin(false, Authority.ADMIN_LEVEL));
        assertFalse(Authority.isAdmin(false, Authority.ADMIN_LEVEL - 1));
        assertFalse(Authority.isAdmin(false, 0));
    }
}
