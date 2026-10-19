package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.api.entity.Mouse.Awaiting.BROKE;
import static com.dwinovo.numen.api.entity.Mouse.Awaiting.REFUSED;
import static com.dwinovo.numen.api.entity.Mouse.Awaiting.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 送了 STOP 之后判"挖掉了、还在挖、被退回"的唯一判据:方块有没有变,服务端还挂不挂着这一格。 */
class MouseAwaitingTest {

    @Test
    void aBlockThatChangedWasBrokenWhateverTheServerStillHolds() {
        assertEquals(BROKE, Mouse.awaiting(true, false));
        assertEquals(BROKE, Mouse.awaiting(true, true));
    }

    @Test
    void aBlockStillThereThatTheServerStillHoldsIsStillBeingDug() {
        assertEquals(WAIT, Mouse.awaiting(false, true));
    }

    @Test
    void aBlockStillThereThatTheServerLetGoOfWasSentBack() {
        assertEquals(REFUSED, Mouse.awaiting(false, false));
    }
}
