package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴生命周期的判断规则。这套测试就是为了钉死那个真机 bug 的病根:
 * 名册的语义必须是"<b>存在</b>",而不是"此刻活在世界里"——把两者混为一谈,
 * 同伴一死就会被当成遣散,本地数据整个删掉。
 */
class CompanionRosterTest {

    private static final long DELAY = 600L;   // 30 秒 = 600 tick
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // ---- 查:注册表 → 名册 ----

    @Test
    void deadCompanionsStayOnTheRoster() {
        List<CompanionRoster.Line> lines = CompanionRoster.build(List.of(
                new CompanionRoster.Row(A, "小焰", 0L),        // 活着
                new CompanionRoster.Row(B, "阿岩", 1000L)      // 死了,刚死
        ), 1000L, DELAY);

        assertEquals(2, lines.size(), "死了不等于不存在——她必须还在名册上");
        assertTrue(lines.stream().anyMatch(l -> l.uuid().equals(B) && l.dead()));
        assertTrue(lines.stream().anyMatch(l -> l.uuid().equals(A) && !l.dead()));
    }

    @Test
    void rosterIsSortedSoThePanelDoesNotJitter() {
        // 注册表是 HashMap,不排序的话面板上同伴的顺序每次推送都可能变
        List<CompanionRoster.Line> lines = CompanionRoster.build(List.of(
                new CompanionRoster.Row(A, "小焰", 0L),
                new CompanionRoster.Row(B, "阿岩", 0L),
                new CompanionRoster.Row(C, "小焰", 0L)         // 重名:再按 uuid 定序
        ), 0L, DELAY);

        assertEquals(List.of("小焰", "小焰", "阿岩"), lines.stream().map(CompanionRoster.Line::name).toList(),
                "按字符串序(不是拼音序)——要的是每次推送都一样,不是好看");
        assertEquals(A, lines.get(0).uuid(), "重名时按 uuid 稳定定序");
    }

    @Test
    void emptyRegistryGivesEmptyRoster() {
        assertTrue(CompanionRoster.build(List.of(), 0L, DELAY).isEmpty());
    }

    // ---- 增:召唤的幂等 ----

    @Test
    void summonReusesTheSameNameInsteadOfMintingADouble() {
        List<CompanionRoster.Row> rows = List.of(
                new CompanionRoster.Row(A, "小焰", 0L),
                new CompanionRoster.Row(B, "阿岩", 0L));

        assertEquals(A, CompanionRoster.findByName(rows, "小焰"), "同名就复用,不铸新的");
        assertNull(CompanionRoster.findByName(rows, "新来的"), "没见过的名字才铸新 UUID");
    }

    @Test
    void deadCompanionIsStillReusedBySummon() {
        // "召唤小焰"时她正躺着等复活——照样是她,不许再铸一只同名分身出来
        assertEquals(A, CompanionRoster.findByName(
                List.of(new CompanionRoster.Row(A, "小焰", 1000L)), "小焰"));
    }

    @Test
    void duplicateNamesResolveTheSameWayEveryTime() {
        // 同名分身(迁移来的数据里可能有):注册表是 HashMap,不定序的话同一句
        // "召唤小焰"今天复用这只、明天复用那只
        List<CompanionRoster.Row> forward = List.of(
                new CompanionRoster.Row(C, "小焰", 0L), new CompanionRoster.Row(A, "小焰", 0L));
        List<CompanionRoster.Row> backward = List.of(
                new CompanionRoster.Row(A, "小焰", 0L), new CompanionRoster.Row(C, "小焰", 0L));

        assertEquals(A, CompanionRoster.findByName(forward, "小焰"));
        assertEquals(CompanionRoster.findByName(forward, "小焰"),
                CompanionRoster.findByName(backward, "小焰"), "顺序不同,答案必须相同");
    }

    @Test
    void namesAreExactNotFuzzy() {
        List<CompanionRoster.Row> rows = List.of(new CompanionRoster.Row(A, "小焰", 0L));
        assertNull(CompanionRoster.findByName(rows, "小"));
        assertNull(CompanionRoster.findByName(rows, "小焰 "));
        assertNull(CompanionRoster.findByName(rows, null));
        assertNull(CompanionRoster.findByName(List.of(), "小焰"));
    }

    // ---- 改:死亡倒计时 ----

    @Test
    void respawnCountdownCountsDown() {
        assertEquals(CompanionRoster.ALIVE, CompanionRoster.respawnInMs(0L, 5000L, DELAY), "没死过");
        assertEquals(DELAY * 50L, CompanionRoster.respawnInMs(1000L, 1000L, DELAY), "刚死:整个窗口");
        assertEquals(300L * 50L, CompanionRoster.respawnInMs(1000L, 1300L, DELAY), "过了一半");
    }

    @Test
    void overdueRespawnReportsZeroNotNegative() {
        // 时候到了但还没落地(主人卡在狭道里),这时候必须是 0:负数会被客户端当成"活着"
        assertEquals(0L, CompanionRoster.respawnInMs(1000L, 1000L + DELAY, DELAY));
        assertEquals(0L, CompanionRoster.respawnInMs(1000L, 999999L, DELAY), "等了很久也还是 0");
        assertTrue(new CompanionRoster.Line(A, "x", 0L).dead(), "0 = 还在等复活,不是活着");
    }

    // ---- 删:哪些家该清 ----

    @Test
    void dismissedCompanionIsSweptButLivingOnesAreNot() {
        List<UUID> gone = CompanionRoster.orphans(
                Map.of(A, "world-1", B, "world-1"), "world-1", Set.of(A));
        assertEquals(List.of(B), gone);
    }

    @Test
    void deadCompanionOnTheRosterIsNeverSwept() {
        // 判错就是毁数据的那条路:她死了、暂时不在世界里,但她还在名册上
        List<UUID> gone = CompanionRoster.orphans(
                Map.of(A, "world-1"), "world-1", Set.of(A));
        assertTrue(gone.isEmpty(), "死亡不是遣散");
    }

    @Test
    void otherWorldsAreNeverTouched() {
        // 换个存档进去,上一个存档的同伴当然不在这份名册上——照着删就是灭顶之灾
        List<UUID> gone = CompanionRoster.orphans(
                Map.of(A, "world-1", B, "world-2"), "world-2", Set.of(B));
        assertTrue(gone.isEmpty(), "world-1 的同伴不归 world-2 的名册管");
    }

    @Test
    void untaggedHomesAreNeverSwept() {
        // 旧版本迁移过来的数据没标世界,归属不明 → 一个都不删(宁可留孤儿)
        java.util.Map<UUID, String> homes = new java.util.HashMap<>();
        homes.put(A, null);
        assertTrue(CompanionRoster.orphans(homes, "world-1", Set.of()).isEmpty());
    }

    @Test
    void unknownWorldSweepsNothing() {
        // 还没收到过名册就不知道自己在哪个世界,这时候一个都不能删
        assertTrue(CompanionRoster.orphans(Map.of(A, "world-1"), null, Set.of()).isEmpty());
        assertTrue(CompanionRoster.orphans(Map.of(A, "world-1"), "  ", Set.of()).isEmpty());
    }

    @Test
    void emptyRosterInAKnownWorldSweepsThatWorldOnly() {
        // 主人把最后一只同伴也遣散了:本世界该清空,别的世界纹丝不动
        List<UUID> gone = CompanionRoster.orphans(
                Map.of(A, "world-1", B, "world-2"), "world-1", Set.of());
        assertEquals(List.of(A), gone);
    }
}
