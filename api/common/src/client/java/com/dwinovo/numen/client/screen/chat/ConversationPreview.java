package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.conversation.Transcript;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 左栏会话列表里每行的"最后一句"(Telegram 那一行淡字):谁说的、说了什么、什么时候。
 * 来源和面板正文同一份——成员日志按会话印归并后的最后一条可见消息;所以左栏和正文永远对得上。
 *
 * <p>归并要排序,左栏每帧要画每一行,所以按"会话 id + 各成员日志行数"缓存:行数没变,最后一句就没变。
 */
public final class ConversationPreview {

    /** 一行:显示文本(多人会话里带说话人),和它的时间戳。 */
    public record Line(String text, long ts) {}

    private record Cached(int lines, Line line) {}

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private ConversationPreview() {}

    /** 这个会话最后一句;还没说过话是 null。 */
    public static Line last(Conversation c) {
        Conversations convos = Conversations.instance();
        Map<UUID, List<ConvoLog.Line>> logs = new LinkedHashMap<>();
        int total = 0;
        for (UUID m : convos.membersAlive(c)) {
            EntityAgentLoop lp = AgentLoopRegistry.get(m).orElse(null);
            if (lp == null) continue;
            List<ConvoLog.Line> lines = lp.display();
            logs.put(m, lines);
            total += lines.size();
        }
        Cached hit = CACHE.get(c.id());
        if (hit != null && hit.lines() == total) {
            return hit.line();
        }
        boolean group = convos.soloOf(c) == null;
        Line out = null;
        List<Transcript.Entry> merged = Transcript.merge(convos.tagOf(c), logs);
        for (int i = merged.size() - 1; i >= 0 && out == null; i--) {
            Transcript.Entry e = merged.get(i);
            if (e.msg() instanceof ConvoState.Msg.User u) {
                String t = flat(ChatDisplayModes.current().userText(u.content()));
                if (!t.isEmpty()) {
                    out = new Line(I18n.get(ModLanguageData.Keys.RAIL_YOU) + ": " + t, e.ts());
                }
            } else if (e.msg() instanceof ConvoState.Msg.Assistant a) {
                String t = flat(ChatDisplayModes.current().assistantText(a.turn().content()));
                if (!t.isEmpty()) {
                    // 群里标谁说的;就他俩不标——只可能是她
                    String who = group ? NumenRoster.instance().name(e.companion()) : null;
                    out = new Line(who == null ? t : who + ": " + t, e.ts());
                }
            }
        }
        CACHE.put(c.id(), new Cached(total, out));
        return out;
    }

    /** 压成一行:换行变空格,好塞进一行淡字里。 */
    private static String flat(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
