package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.conversation.Mentions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @} 补全:只补光标左边那个以 @ 开头的词,换成 {@code @名字 },其余文字不动。 */
class MentionCompletionsTest {

    private static final List<Mentions.Member> GROUP = List.of(
            new Mentions.Member(UUID.randomUUID(), "小柚"),
            new Mentions.Member(UUID.randomUUID(), "阿岚"),
            new Mentions.Member(UUID.randomUUID(), "Anna"));

    @Test
    void aBareAtListsEveryone() {
        List<Completion> out = MentionCompletions.complete("@", 1, GROUP);
        assertEquals(List.of("@小柚", "@阿岚", "@Anna"), out.stream().map(Completion::label).toList());
        assertEquals("@小柚 ", out.get(0).insert());
    }

    @Test
    void prefixNarrowsCaseInsensitively() {
        List<Completion> out = MentionCompletions.complete("@an", 3, GROUP);
        assertEquals(1, out.size());
        assertEquals("@Anna ", out.get(0).insert());
    }

    @Test
    void onlyTheWordUnderTheCursorIsReplaced() {
        String text = "@阿岚 你和 @小 一起去";
        int cursor = text.indexOf("@小") + 2;
        List<Completion> out = MentionCompletions.complete(text, cursor, GROUP);
        assertEquals(1, out.size());
        assertEquals("@阿岚 你和 @小柚  一起去", out.get(0).insert());
    }

    @Test
    void nothingWithoutAnAtAtTheCursor() {
        assertTrue(MentionCompletions.complete("去挖铁", 3, GROUP).isEmpty());
        assertTrue(MentionCompletions.complete("邮箱 a@b", 6, GROUP).isEmpty(), "词中间的 @ 不是点名");
        assertTrue(MentionCompletions.complete("@", 1, List.of()).isEmpty());
    }

    @Test
    void twoMembersSharingANameShowOnce() {
        List<Mentions.Member> twins = List.of(
                new Mentions.Member(UUID.randomUUID(), "小柚"),
                new Mentions.Member(UUID.randomUUID(), "小柚"));
        assertEquals(1, MentionCompletions.complete("@", 1, twins).size());
    }
}
