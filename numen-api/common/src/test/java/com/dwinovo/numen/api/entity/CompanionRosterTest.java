package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴名册的判断规则:名册的语义是"<b>存在</b>"(注册表里有这一条),而不是"此刻站在世界里"——
 * 把两者混为一谈,休眠的同伴就会被当成遣散,本地数据整个删掉。
 */
class CompanionRosterTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // ---- 查:注册表 → 名册 ----

    @Test
    void rosterIsSortedSoThePanelDoesNotJitter() {
        // 注册表是 HashMap,不排序的话面板上同伴的顺序每次推送都可能变
        List<CompanionRoster.Row> rows = CompanionRoster.build(List.of(
                new CompanionRoster.Row(A, "小焰"),
                new CompanionRoster.Row(B, "阿岩"),
                new CompanionRoster.Row(C, "小焰")         // 重名:再按 uuid 定序
        ));

        assertEquals(List.of("小焰", "小焰", "阿岩"), rows.stream().map(CompanionRoster.Row::name).toList(),
                "按字符串序(不是拼音序)——要的是每次推送都一样,不是好看");
        assertEquals(A, rows.get(0).uuid(), "重名时按 uuid 稳定定序");
    }

    @Test
    void emptyRegistryGivesEmptyRoster() {
        assertTrue(CompanionRoster.build(List.of()).isEmpty());
    }

    // ---- 增:召唤的幂等 ----

    @Test
    void summonReusesTheSameNameInsteadOfMintingADouble() {
        List<CompanionRoster.Row> rows = List.of(
                new CompanionRoster.Row(A, "小焰"),
                new CompanionRoster.Row(B, "阿岩"));

        assertEquals(A, CompanionRoster.findByName(rows, "小焰"), "同名就复用,不铸新的");
        assertNull(CompanionRoster.findByName(rows, "新来的"), "没见过的名字才铸新 UUID");
    }

    @Test
    void duplicateNamesResolveTheSameWayEveryTime() {
        // 同名分身(迁移来的数据里可能有):注册表是 HashMap,不定序的话同一句
        // "召唤小焰"今天复用这只、明天复用那只
        List<CompanionRoster.Row> forward = List.of(
                new CompanionRoster.Row(C, "小焰"), new CompanionRoster.Row(A, "小焰"));
        List<CompanionRoster.Row> backward = List.of(
                new CompanionRoster.Row(A, "小焰"), new CompanionRoster.Row(C, "小焰"));

        assertEquals(A, CompanionRoster.findByName(forward, "小焰"));
        assertEquals(CompanionRoster.findByName(forward, "小焰"),
                CompanionRoster.findByName(backward, "小焰"), "顺序不同,答案必须相同");
    }

    @Test
    void namesAreExactNotFuzzy() {
        List<CompanionRoster.Row> rows = List.of(new CompanionRoster.Row(A, "小焰"));
        assertNull(CompanionRoster.findByName(rows, "小"));
        assertNull(CompanionRoster.findByName(rows, "小焰 "));
        assertNull(CompanionRoster.findByName(rows, null));
        assertNull(CompanionRoster.findByName(List.of(), "小焰"));
    }
}
