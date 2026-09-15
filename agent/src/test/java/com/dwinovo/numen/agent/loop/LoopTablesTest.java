package com.dwinovo.numen.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 停牌解开表与切断表逐格钉住:行为差异只许出自这两张表。 */
class LoopTablesTest {

    private static Set<Hold.Release> releasers(Hold hold) {
        Set<Hold.Release> out = EnumSet.noneOf(Hold.Release.class);
        for (Hold.Release r : Hold.Release.values()) {
            if (hold.releasedBy(r)) {
                out.add(r);
            }
        }
        return out;
    }

    @Test
    void holdReleaseTable() {
        assertEquals(EnumSet.of(Hold.Release.RESPAWN), releasers(Hold.DEAD));
        assertEquals(EnumSet.noneOf(Hold.Release.class), releasers(Hold.EXTERNAL), "外接驾驶是现算的,不经解开");
        assertEquals(EnumSet.of(Hold.Release.OWNER_SPOKE), releasers(Hold.OWNER_STOP), "停止只有主人再开口能解开");
        assertEquals(EnumSet.of(Hold.Release.OWNER_SPOKE, Hold.Release.BINDING_CHANGED), releasers(Hold.BLOCKED));
        assertEquals(EnumSet.of(Hold.Release.OWNER_SPOKE, Hold.Release.URGENT), releasers(Hold.FAILED),
                "一次失败不能把链条永久锁住:急件同样解开");
    }

    @Test
    void haltTable() {
        assertTrue(HaltReason.OWNER_STOP.stopsBody());
        assertTrue(HaltReason.OWNER_STOP.clearsInterrupted());
        assertTrue(HaltReason.OWNER_STOP.endsGoal());
        assertEquals(Hold.OWNER_STOP, HaltReason.OWNER_STOP.enters());

        assertEquals(Hold.DEAD, HaltReason.DEATH.enters());
        for (HaltReason r : EnumSet.of(HaltReason.DEATH, HaltReason.DISCONNECT, HaltReason.EXTERNAL, HaltReason.DISPOSE)) {
            assertFalse(r.stopsBody(), r + " 不叫停身体");
            assertFalse(r.clearsInterrupted(), r + " 不清队列");
            assertFalse(r.endsGoal(), r + " 不结束目标");
        }
        assertNull(HaltReason.DISCONNECT.enters(), "登出不置停牌");
        assertNull(HaltReason.EXTERNAL.enters(), "外接驾驶现算,不存");
        assertNull(HaltReason.DISPOSE.enters());
    }

    @Test
    void haltWordsCarryTheDetail() {
        assertEquals("你死了(掉进岩浆)", HaltReason.DEATH.words("掉进岩浆"));
        assertEquals("被主人打断", HaltReason.OWNER_STOP.words(null));
    }
}
