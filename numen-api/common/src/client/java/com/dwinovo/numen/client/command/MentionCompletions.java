package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.conversation.Mentions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 输入行里 {@code @} 的补全:光标所在的词以 {@code @} 开头时,列出会话里名字以它开头的成员。
 * 和斜杠命令共用同一条候选管线({@link Completion}),只是来源换成成员表;选中一行是把
 * 那个词换成 {@code @名字 },其余文字原样保留。
 *
 * <p>名字来自 {@link Mentions.Member},和路由用的是同一份——补出来的正好是 {@code @} 得到的。
 * 纯逻辑,不碰 Minecraft。
 */
public final class MentionCompletions {

    private MentionCompletions() {}

    /**
     * @param text    输入框全文
     * @param cursor  光标下标(补的是光标左边那个词)
     * @param members 会话里还在的成员
     */
    public static List<Completion> complete(String text, int cursor, List<Mentions.Member> members) {
        if (text == null || members == null || members.isEmpty()) {
            return List.of();
        }
        int at = Math.max(0, Math.min(cursor, text.length()));
        int start = at;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        if (start >= at || text.charAt(start) != '@') {
            return List.of();
        }
        String prefix = text.substring(start + 1, at).toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        for (Mentions.Member m : members) {
            if (m.name() != null && !m.name().isBlank()
                    && m.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(m.name());
            }
        }
        List<Completion> out = new ArrayList<>();
        for (String name : names) {
            String mention = "@" + name;
            out.add(Completion.of(text.substring(0, start) + mention + " " + text.substring(at),
                    mention, ""));
        }
        return out;
    }
}
