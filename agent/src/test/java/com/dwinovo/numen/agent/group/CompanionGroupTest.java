package com.dwinovo.numen.agent.group;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 群:一个名字 + 一串同伴 + 当前话头。守的是"话头只可能落在成员身上"和默认名跟着成员走。 */
class CompanionGroupTest {

    private static final UUID YOU = UUID.randomUUID();
    private static final UUID LAN = UUID.randomUUID();
    private static final UUID MEI = UUID.randomUUID();

    private static final Map<UUID, String> NAMES = Map.of(YOU, "小柚", LAN, "阿岚", MEI, "小梅");

    private static String nameOf(UUID u) {
        return NAMES.get(u);
    }

    @Test
    void aNewGroupHasNoNameAndNoFloor() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN));
        assertNull(g.name(), "建群那一步不问名字");
        assertTrue(g.floor().isEmpty(), "话头空着 = 全体");
        assertEquals("小柚、阿岚", g.displayName(CompanionGroupTest::nameOf));
    }

    /** 默认名跟着成员走,主人一旦改名就固定——不会出现"群里早没有阿岚了,群名还叫阿岚"。 */
    @Test
    void theDefaultNameFollowsTheMembersUntilTheOwnerRenamesIt() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN));
        assertEquals("小柚、小梅",
                g.withMembers(List.of(YOU, MEI)).displayName(CompanionGroupTest::nameOf));

        CompanionGroup named = g.withName("挖矿队");
        assertEquals("挖矿队",
                named.withMembers(List.of(YOU, MEI)).displayName(CompanionGroupTest::nameOf));
    }

    @Test
    void clearingTheNameFallsBackToTheMembers() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN)).withName("挖矿队").withName("  ");
        assertNull(g.name());
        assertEquals("小柚、阿岚", g.displayName(CompanionGroupTest::nameOf));
    }

    @Test
    void theFloorOnlyEverLandsOnMembers() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN)).withFloor(List.of(YOU, MEI));
        assertEquals(List.of(YOU), g.floor(), "小梅不在群里,收不下");
    }

    /** 移出一个成员,话头跟着掉——否则会剩一个指向群外的话头。 */
    @Test
    void removingAMemberDropsHerFromTheFloor() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN)).withFloor(List.of(LAN));
        assertEquals(List.of(), g.withMembers(List.of(YOU)).floor());
    }

    @Test
    void membersAreDeduplicatedAndKeepTheirOrder() {
        CompanionGroup g = CompanionGroup.of(List.of(LAN, YOU, LAN));
        assertEquals(List.of(LAN, YOU), g.members());
    }

    @Test
    void itSurvivesARoundTripThroughJson() {
        CompanionGroup g = CompanionGroup.of(List.of(YOU, LAN)).withName("挖矿队").withFloor(List.of(LAN));
        CompanionGroup back = CompanionGroup.fromJson(
                JsonParser.parseString(g.toJson().toString()).getAsJsonObject());

        assertEquals(g.id(), back.id());
        assertEquals("挖矿队", back.name());
        assertEquals(List.of(YOU, LAN), back.members());
        assertEquals(List.of(LAN), back.floor());
    }

    @Test
    void aGroupWithNoMembersIsNotAGroup() {
        assertNull(CompanionGroup.fromJson(
                JsonParser.parseString("{\"id\":\"x\",\"members\":[]}").getAsJsonObject()));
        assertNull(CompanionGroup.fromJson(
                JsonParser.parseString("{\"members\":[\"" + YOU + "\"]}").getAsJsonObject()));
    }

    /** 手改坏的一行不该拖垮整个群。 */
    @Test
    void aBrokenMemberLineIsSkipped() {
        CompanionGroup g = CompanionGroup.fromJson(JsonParser.parseString(
                "{\"id\":\"x\",\"members\":[\"" + YOU + "\",\"not-a-uuid\"]}").getAsJsonObject());
        assertEquals(List.of(YOU), g.members());
    }
}
