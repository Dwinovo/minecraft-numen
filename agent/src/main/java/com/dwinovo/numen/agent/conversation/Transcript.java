package com.dwinovo.numen.agent.conversation;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * 一个会话的记录——从成员各自的日志里归并出来,不另存。
 *
 * <p>存储按人,视图按会话(见 docs/group-chat.md §七):每只同伴一本日志,说出口的行盖着会话印;
 * 这里把成员的日志按印过滤、按时间交错。主人的一句话会被复制进每个醒着的成员的日志,各自
 * 盖各自的时间戳,所以副本靠<b>发言号 + 原文</b>归成一条(见 {@link Conversation#turn}),不看时间。
 * 没挂发言号的行(单成员会话)从不合并——那本来就只有一份。
 *
 * <p>面板和外脑都从这一个口读"这个会话发生了什么"。纯 JVM。
 */
public final class Transcript {

    /** 会话里的一行:什么时候、谁那本日志、消息本身。 */
    public record Entry(long ts, UUID companion, ConvoState.Msg msg) {}

    private Transcript() {}

    /**
     * @param conversation 会话印;null = 就他俩
     * @param logs         成员 → 她的日志行(面板视图)
     */
    public static List<Entry> merge(String conversation, Map<UUID, List<ConvoLog.Line>> logs) {
        List<Entry> all = new ArrayList<>();
        for (Map.Entry<UUID, List<ConvoLog.Line>> e : logs.entrySet()) {
            for (ConvoLog.Line line : e.getValue()) {
                if (Objects.equals(line.conv(), conversation)) {
                    all.add(new Entry(line.ts(), e.getKey(), line.msg()));
                }
            }
        }
        // 先按时间排,再去重:同一句话的几份副本里留最早落盘的那份
        all.sort(Comparator.comparingLong(Entry::ts));
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>(all.size());
        for (Entry entry : all) {
            String key = copyKey(entry.msg());
            if (key != null && !seen.add(key)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * 主人那句话的副本键:发言号 + 原文。加原文是为了斜杠命令——命令不经 say、不拨号,只靠号会和
     * 上一句撞;原文一并比就分得开。不是主人的话、或者没挂发言号,返回 null = 不参与去重。
     */
    private static String copyKey(ConvoState.Msg msg) {
        if (!(msg instanceof ConvoState.Msg.User u)) {
            return null;
        }
        OptionalInt turn = Audience.turnOf(u.content());
        if (turn.isEmpty()) {
            return null;
        }
        return turn.getAsInt() + "\n" + String.join("\n", ConvoLog.queries(u.content()));
    }
}
