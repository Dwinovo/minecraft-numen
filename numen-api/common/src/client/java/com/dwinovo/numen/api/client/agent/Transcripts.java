package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.llm.ConvoLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 面板正在看的会话记录,从服务端来的:翻页取回的老行加上之后实时推来的新行,按会话各一条时间线。内存里的视图,不落盘、
 * 不是任何东西的副本——真源是服务端那几本日志,关了面板就丢,再开重新取最新一页。
 *
 * <p>一个会话的记录在服务端已经归并过(群里主人的一句话在每个成员那儿各有一份副本,归成一行)。实时推来的行是各成员的
 * 日志各推各的,所以新来的主人的话要按同一个键({@link Transcript#copyKey})并进已有的那一行,而不是画两遍。
 *
 * <p>线程:客户端主线程。
 */
public final class RemoteTranscripts {

    /** 一个会话的时间线,和再往前翻的状态。 */
    private static final class Line {
        final List<Transcript.Entry> entries = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final Set<String> copies = new HashSet<>();
        String cursor = "";
        boolean more = true;
        boolean loaded;
        boolean loading;
    }

    private final Map<String, Line> lines = new HashMap<>();

    private Line of(String conversation) {
        return lines.computeIfAbsent(conversation, id -> new Line());
    }

    /** 这个会话已读到手的记录,从老到新。 */
    public List<Transcript.Entry> entries(String conversation) {
        Line line = lines.get(conversation);
        return line == null ? List.of() : List.copyOf(line.entries);
    }

    /** 最新一页还没取过,也没在取:该去要了。 */
    public boolean wantsFirstPage(String conversation) {
        Line line = of(conversation);
        return !line.loaded && !line.loading;
    }

    /** 再往前还有、而且没在取:往上翻到头时该去要了。 */
    public boolean wantsOlder(String conversation) {
        Line line = of(conversation);
        return line.loaded && line.more && !line.loading;
    }

    /** 要下一页的游标;最新一页是空串。 */
    public String cursor(String conversation) {
        return of(conversation).cursor;
    }

    /** 请求发出去了。 */
    public void asked(String conversation) {
        of(conversation).loading = true;
    }

    /** 一页回来了:老行接在时间线前面(已有的行编号不重复),游标与"还有没有"换成这页带回来的。 */
    public void page(String conversation, List<Transcript.Entry> older, String cursor, boolean more) {
        Line line = of(conversation);
        List<Transcript.Entry> fresh = new ArrayList<>();
        for (Transcript.Entry entry : older) {
            if (line.ids.addAll(entry.ids().values())) {
                remember(line, entry);
                fresh.add(entry);
            }
        }
        line.entries.addAll(0, fresh);
        line.cursor = cursor;
        line.more = more;
        line.loaded = true;
        line.loading = false;
    }

    /**
     * 实时推来的一行。还没取过最新一页的会话不收:那一页回来时会带上它。主人的话的副本并进已有的那一行。
     *
     * @param conversation 这一行属于哪个会话
     */
    public void live(String conversation, UUID companion, ConvoLog.Line row) {
        Line line = lines.get(conversation);
        if (line == null || !line.loaded || row.id() != null && line.ids.contains(row.id())) {
            return;
        }
        String copy = Transcript.copyKey(row.msg());
        if (copy != null && line.copies.contains(copy)) {
            line.ids.add(row.id());
            return;
        }
        Map<UUID, String> ids = new HashMap<>();
        ids.put(companion, row.id());
        Transcript.Entry entry = new Transcript.Entry(row.ts(), companion, row.msg(), ids);
        line.ids.add(row.id());
        remember(line, entry);
        line.entries.add(entry);
    }

    private static void remember(Line line, Transcript.Entry entry) {
        String copy = Transcript.copyKey(entry.msg());
        if (copy != null) {
            line.copies.add(copy);
        }
    }

    /** 这个会话的记录不要了(关了面板、离开会话)。 */
    public void forget(String conversation) {
        lines.remove(conversation);
    }

    /** 全部不要了(断线)。 */
    public void clear() {
        lines.clear();
    }
}
