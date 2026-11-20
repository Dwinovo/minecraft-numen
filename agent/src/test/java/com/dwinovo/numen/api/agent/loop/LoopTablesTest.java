package com.dwinovo.numen.api.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopTablesTest {

    @Test
    void haltWordsCarryTheDetail() {
        assertEquals("you died (掉进岩浆)", HaltReason.DEATH.words("掉进岩浆"));
        assertEquals("interrupted by the owner", HaltReason.OWNER_STOP.words(null));
    }
}
