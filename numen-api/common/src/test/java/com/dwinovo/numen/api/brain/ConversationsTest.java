package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话的成员表:拉人、移出、改名、解散、遣散之后的收尾,以及落盘后重进读回来。送话的路由在 GameTest 里走真大脑。 */
class ConversationsTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private final Map<UUID, String> roster = new LinkedHashMap<>(Map.of(A, "阿岚", B, "小柚", C, "团子"));

    private Conversations open(Path dir) {
        return new Conversations(dir.resolve("conversations.json"), Io.direct(e -> { }), new Conversations.Roster() {
            @Override
            public List<UUID> companions() {
                return new ArrayList<>(roster.keySet());
            }

            @Override
            public String name(UUID companion) {
                return roster.get(companion);
            }
        }, id -> Optional.empty(), () -> "主人");
    }

    @Test
    void pullingSomeoneIntoAPrivateChatStartsAnotherConversationAndLeavesThePrivateOneBe(@TempDir Path dir) {
        Conversations rooms = open(dir);
        Conversation solo = rooms.of(A);
        assertNull(rooms.tagOf(solo), "没人动过它就没东西存");

        Conversation group = rooms.pullIn(solo, B);

        assertEquals(List.of(A, B), group.members());
        assertEquals(group.id(), rooms.tagOf(group));
        assertEquals(A.toString(), rooms.of(A).id(), "她的私聊还是私聊");
        assertEquals(group.id(), rooms.pullIn(group, C).id(), "从落过盘的会话拉人是扩它");
    }

    @Test
    void theMemberTableIsReadBackAfterAReopen(@TempDir Path dir) {
        Conversations rooms = open(dir);
        Conversation group = rooms.rename(rooms.pullIn(rooms.of(A), B), "挖矿队");

        Conversation back = open(dir).get(group.id());

        assertEquals("挖矿队", back.name());
        assertEquals(List.of(A, B), back.members());
    }

    @Test
    void aDismissedCompanionLeavesTheGroupAndAGroupOfOneDissolves(@TempDir Path dir) {
        Conversations rooms = open(dir);
        Conversation group = rooms.pullIn(rooms.of(A), B);

        roster.remove(B);
        rooms.left(B, "小柚");

        assertNull(rooms.get(group.id()), "只剩一个人的群随即解散");
        assertEquals(List.of(A), rooms.membersAlive(group), "成员表按名册过滤");
        assertFalse(rooms.all().stream().anyMatch(c -> c.id().equals(group.id())));
    }

    @Test
    void aGroupWithTwoLeftStaysAGroup(@TempDir Path dir) {
        Conversations rooms = open(dir);
        Conversation group = rooms.pullIn(rooms.pullIn(rooms.of(A), B), C);

        roster.remove(C);
        rooms.left(C, "团子");

        assertTrue(rooms.get(group.id()) != null);
    }

    @Test
    void whoIsListeningIsTheOthersByNameAndTheTurnNumber(@TempDir Path dir) {
        Conversations rooms = open(dir);
        Conversation group = rooms.pullIn(rooms.pullIn(rooms.of(A), B), C);

        String line = rooms.audienceLine(A, group.id());

        assertTrue(line.contains("小柚") && line.contains("团子") && !line.contains("阿岚"), line);
        assertEquals("", rooms.audienceLine(A, null), "就他俩时没人听得见");
    }
}
