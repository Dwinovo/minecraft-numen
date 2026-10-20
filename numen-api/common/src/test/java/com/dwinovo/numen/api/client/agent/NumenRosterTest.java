package com.dwinovo.numen.api.client.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端这份"我有哪些同伴"的镜像。它是单例、跨存档活着,所以<b>换存档要清干净</b>必须钉死:
 * 残留会让下一个世界的第一份名册看起来少了一堆人,而少了就意味着删数据。
 */
class NumenRosterTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    @AfterEach
    void empty() {
        NumenRoster.instance().clear();
    }

    @Test
    void replaceAllIsAFullSwapNotAMerge() {
        NumenRoster.instance().replaceAll("w1", List.of(
                new NumenRoster.Entry(A, "小焰"), new NumenRoster.Entry(B, "阿岩")));
        assertEquals(2, NumenRoster.instance().size());

        NumenRoster.instance().replaceAll("w1", List.of(new NumenRoster.Entry(A, "小焰")));
        assertEquals(1, NumenRoster.instance().size(), "服务端每次推的都是完整名册,旧的不许留");
        assertNull(NumenRoster.instance().name(B));
    }

    @Test
    void clearDropsTheWorldTooSoTheNextSaveStartsBlank() {
        NumenRoster.instance().replaceAll("w1", List.of(new NumenRoster.Entry(A, "小焰")));
        assertEquals("w1", NumenRoster.instance().worldId());

        NumenRoster.instance().clear();

        assertEquals(0, NumenRoster.instance().size());
        assertNull(NumenRoster.instance().worldId(),
                "世界身份也得作废——否则换存档时会拿旧世界的 id 去对账");
    }

    @Test
    void aCompanionRemovedFromTheServerIsShownAsOut() {
        NumenRoster.instance().replaceAll("w1", List.of(
                new NumenRoster.Entry(A, "小焰", false, "cheating at cards")));

        assertTrue(NumenRoster.instance().isRemoved(A));
        assertEquals("cheating at cards", NumenRoster.instance().removedFor(A));
    }

    @Test
    void unknownCompanionIsJustAbsent() {
        assertFalse(NumenRoster.instance().isRemoved(A));
        assertNull(NumenRoster.instance().name(A));
    }

    @Test
    void blankWorldIdReadsAsUnknown() {
        // 老版本服务端不发世界 id;这时候必须是 null,对账才会整段跳过
        NumenRoster.instance().replaceAll("", List.of(new NumenRoster.Entry(A, "小焰")));
        assertNull(NumenRoster.instance().worldId());
    }
}
