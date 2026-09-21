package com.dwinovo.numen.agent.conversation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 群:一个名字 + 一串同伴 + 当前话头。守的是"话头只可能落在成员身上"和默认名跟着成员走。 */
class ConversationTest {

    private static final UUID YOU = UUID.randomUUID();
    private static final UUID LAN = UUID.randomUUID();
    private static final UUID MEI = UUID.randomUUID();

    private static final Map<UUID, String> NAMES = Map.of(YOU, "小柚", LAN, "阿岚", MEI, "小梅");

    private static String nameOf(UUID u) {
        return NAMES.get(u);
    }

    @Test
    void aNewGroupHasNoNameAndNoFloor() {
        Conversation g = Conversation.of(List.of(YOU, LAN));
        assertNull(g.name(), "建群那一步不问名字");
        assertTrue(g.floor().isEmpty(), "话头空着 = 全体");
        assertEquals("小柚、阿岚", g.displayName(ConversationTest::nameOf));
    }

    /** 默认名跟着成员走,主人一旦改名就固定——不会出现"群里早没有阿岚了,群名还叫阿岚"。 */
    @Test
    void theDefaultNameFollowsTheMembersUntilTheOwnerRenamesIt() {
        Conversation g = Conversation.of(List.of(YOU, LAN));
        assertEquals("小柚、小梅",
                g.withMembers(List.of(YOU, MEI)).displayName(ConversationTest::nameOf));

        Conversation named = g.withName("挖矿队");
        assertEquals("挖矿队",
                named.withMembers(List.of(YOU, MEI)).displayName(ConversationTest::nameOf));
    }

    @Test
    void clearingTheNameFallsBackToTheMembers() {
        Conversation g = Conversation.of(List.of(YOU, LAN)).withName("挖矿队").withName("  ");
        assertNull(g.name());
        assertEquals("小柚、阿岚", g.displayName(ConversationTest::nameOf));
    }

    @Test
    void theFloorOnlyEverLandsOnMembers() {
        Conversation g = Conversation.of(List.of(YOU, LAN)).withFloor(List.of(YOU, MEI));
        assertEquals(List.of(YOU), g.floor(), "小梅不在群里,收不下");
    }

    /** 移出一个成员,话头跟着掉——否则会剩一个指向群外的话头。 */
    @Test
    void removingAMemberDropsHerFromTheFloor() {
        Conversation g = Conversation.of(List.of(YOU, LAN)).withFloor(List.of(LAN));
        assertEquals(List.of(), g.withMembers(List.of(YOU)).floor());
    }

    @Test
    void membersAreDeduplicatedAndKeepTheirOrder() {
        Conversation g = Conversation.of(List.of(LAN, YOU, LAN));
        assertEquals(List.of(LAN, YOU), g.members());
    }

    @Test
    void itSurvivesARoundTripThroughJson() {
        Conversation g = Conversation.of(List.of(YOU, LAN)).withName("挖矿队").withFloor(List.of(LAN));
        Conversation back = Conversation.fromJson(
                JsonParser.parseString(g.toJson().toString()).getAsJsonObject());

        assertEquals(g.id(), back.id());
        assertEquals("挖矿队", back.name());
        assertEquals(List.of(YOU, LAN), back.members());
        assertEquals(List.of(LAN), back.floor());
    }

    @Test
    void aGroupWithNoMembersIsNotAGroup() {
        assertNull(Conversation.fromJson(
                JsonParser.parseString("{\"id\":\"x\",\"members\":[]}").getAsJsonObject()));
        assertNull(Conversation.fromJson(
                JsonParser.parseString("{\"members\":[\"" + YOU + "\"]}").getAsJsonObject()));
    }

    /** 手改坏的一行不该拖垮整个群。 */
    @Test
    void aBrokenMemberLineIsSkipped() {
        Conversation g = Conversation.fromJson(JsonParser.parseString(
                "{\"id\":\"x\",\"members\":[\"" + YOU + "\",\"not-a-uuid\"]}").getAsJsonObject());
        assertEquals(List.of(YOU), g.members());
    }
}
