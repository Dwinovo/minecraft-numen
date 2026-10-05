package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.conversation.Conversation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同伴被遣散后,哪些会话随之解散:群只剩一个人(或没人)就解散;落过盘的"就他俩"不动。 */
class ConversationsDissolveTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void aGroupLeftWithOneMemberDissolves() {
        Conversation trio = new Conversation("c_trio", "三人行", List.of(A, B, C), 0);
        assertTrue(Conversations.dissolvesAfterLeaving(trio, 1), "三人走了两个,只剩一个");
        assertTrue(Conversations.dissolvesAfterLeaving(trio, 0), "都走了");
    }

    @Test
    void aGroupWithTwoLeftStaysAGroup() {
        Conversation trio = new Conversation("c_trio", null, List.of(A, B, C), 0);
        assertFalse(Conversations.dissolvesAfterLeaving(trio, 2), "还剩两个就还是群");
    }

    @Test
    void aSavedPrivateChatIsNotAGroupToDissolve() {
        Conversation solo = new Conversation(A.toString(), null, List.of(A), 0);
        assertFalse(Conversations.dissolvesAfterLeaving(solo, 1), "本来就一个人的记录不是群");
    }
}
