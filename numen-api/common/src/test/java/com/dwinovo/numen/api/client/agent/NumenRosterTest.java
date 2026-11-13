package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.entity.Keeper;
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
                new NumenRoster.Entry(A, "小焰", false,
                        com.dwinovo.numen.api.entity.Attendance.Outside.byServer("cheating at cards"), List.of())));

        assertTrue(NumenRoster.instance().outside(A).server());
        assertEquals("cheating at cards", NumenRoster.instance().outside(A).reason());
    }

    private static final UUID STEVE = UUID.fromString("3f9a1c07-4b2e-4d11-9a55-0123456789ab");
    private static final UUID ALEX = UUID.fromString("0a1b2c07-4b2e-4d11-9a55-0123456789ab");

    private static NumenRoster.Entry shared() {
        return new NumenRoster.Entry(A, "小焰", false, com.dwinovo.numen.api.entity.Attendance.Outside.NONE,
                List.of(new Keeper(STEVE, "Steve"), new Keeper(ALEX, "")));
    }

    @Test
    void theFirstOnHerOwnerTableIsHerCreator() {
        NumenRoster.Entry her = shared();

        assertEquals(STEVE, her.creator().uuid());
        assertTrue(her.createdBy(STEVE));
        assertFalse(her.createdBy(ALEX), "分享来的:创建者另有其人");
        assertFalse(her.createdBy(null), "没在世界里的玩家谁也不是");
        assertNull(new NumenRoster.Entry(B, "阿岩").creator(), "主人表没有说");
        assertFalse(new NumenRoster.Entry(B, "阿岩").createdBy(STEVE), "不知道是谁创建的,只有创建者的入口不给");
        assertEquals("player@0a1b2c", her.keepers().get(1).shown(), "名字查不到的写他的身份");
    }

    @Test
    void theSpeakerMarkedInHerLogIsTheOneOnHerOwnerTable() {
        NumenRoster.instance().replaceAll("w1", List.of(shared()));
        NumenRoster roster = NumenRoster.instance();

        assertEquals(new Keeper(STEVE, "Steve"), roster.speaker(A, "player@3f9a1c (Steve)"));
        assertEquals(new Keeper(STEVE, "Steve"), roster.speaker(A, "player@3f9a1c (OldName)"),
                "认的是身份,名字照表上现在的");
        assertEquals(new Keeper(ALEX, ""), roster.speaker(A, "player@0a1b2c"));
    }

    @Test
    void wordsAreAnotherOwnersOnlyWhenTheLogMarksSomeoneElse() {
        NumenRoster.instance().replaceAll("w1", List.of(shared(), new NumenRoster.Entry(B, "阿岩")));
        NumenRoster roster = NumenRoster.instance();

        assertEquals(new Keeper(STEVE, "Steve"),
                roster.otherSpeaker(A, "<query from=\"player@3f9a1c (Steve)\">修一下镐子</query>", ALEX));
        assertNull(roster.otherSpeaker(A, "<query from=\"player@3f9a1c (Steve)\">修一下镐子</query>", STEVE), "自己说的");
        assertNull(roster.otherSpeaker(A, "<query>修一下镐子</query>", ALEX), "没标是谁说的:她那时只有一位主人");
        assertNull(roster.otherSpeaker(A, "<query source=\"schedule\">该睡了</query>", ALEX), "定时说的,不是谁亲口说的");
        assertTrue(roster.severalKeepers(A));
        assertFalse(roster.severalKeepers(B), "主人表没有说的不算");
        assertFalse(roster.severalKeepers(UUID.randomUUID()), "不在名册上");
    }

    @Test
    void aSpeakerWhoLeftHerOwnerTableKeepsTheNameTheLogWrote() {
        NumenRoster.instance().replaceAll("w1", List.of(shared()));
        NumenRoster roster = NumenRoster.instance();

        Keeper gone = roster.speaker(A, "player@ffffff (Herobrine)");
        assertEquals("Herobrine", gone.name());
        assertEquals(gone, roster.speaker(A, "player@ffffff (Herobrine)"), "同一位永远是同一个:连发、名字色靠它认");
        assertFalse(gone.uuid().equals(STEVE) || gone.uuid().equals(ALEX));
        assertEquals("player@ffffff", roster.speaker(A, "player@ffffff").shown(), "没带名字的写他的身份");
        assertNull(roster.speaker(A, "not a label"), "读不懂");
        assertNull(roster.speaker(B, "player@3f9a1c (Steve)"), "名册上没有她");
    }

    @Test
    void unknownCompanionIsJustAbsent() {
        assertFalse(NumenRoster.instance().outside(A).isOutside());
        assertNull(NumenRoster.instance().name(A));
    }

    @Test
    void blankWorldIdReadsAsUnknown() {
        // 老版本服务端不发世界 id;这时候必须是 null,对账才会整段跳过
        NumenRoster.instance().replaceAll("", List.of(new NumenRoster.Entry(A, "小焰")));
        assertNull(NumenRoster.instance().worldId());
    }
}
