package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.task.TaskResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tool implementations — the business half of {@code memory} ({@code MemoryCommands}).
 * These run on the agent thread with no server body and return their result directly.
 */
public final class AgentOps {

    // ---- 札记 ----

    /** 不点名的札记用的名字的前缀:{@code note-1}、{@code note-2}…… */
    private static final String UNNAMED = "note-";

    /**
     * 记一条。天数由札记本盖,回执只在条数到顶时多说一句——删哪条是她的事。{@code name} 为 null 时用还没用过的
     * {@code note-N} 里最小的那个。
     */
    public String remember(UUID companion, String name, String description, String type,
                           String content) {
        NoteBook book = NoteBook.of(companion);
        String chosen = name;
        if (chosen == null) {
            java.util.Set<String> taken = new java.util.HashSet<>();
            book.index().forEach(n -> taken.add(n.name()));
            int k = 1;
            while (taken.contains(UNNAMED + k)) {
                k++;
            }
            chosen = UNNAMED + k;
        }
        NoteBook.Note note = book.write(chosen, description, type, content);
        int count = book.index().size();
        return TaskResult.ok(count >= NoteBook.SOFT_MAX
                ? "remembered " + note.name() + " — you now keep " + count + " notes; look for ones to merge or forget"
                : "remembered " + note.name(), Map.of("name", note.name(), "count", count)).toJson();
    }

    /**
     * 读一条的正文,正文按输出预算一页一页给。没有这条就把有的名字列出来——她记的名字自己最清楚,别让她瞎猜。
     *
     */
    public String recall(UUID companion, String name, CommandArgs args) {
        NoteBook book = NoteBook.of(companion);
        NoteBook.Note note = book.read(name);
        if (note == null) {
            String known = book.index().stream().map(NoteBook.Note::name).collect(Collectors.joining(", "));
            return TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.NOT_FOUND, "no note named " + name
                    + "; your notes are: " + (known.isEmpty() ? "(none)" : known), null).toJson();
        }
        TaskResult content = new Listing("", List.of(note.content().split("\n", -1)), "").result(args);
        if (!content.success()) {
            return content.toJson();
        }
        // 正文也在数据里:脚本里读回来的是这张表,正文就是 content
        return TaskResult.ok(content.message(), Map.of("name", note.name(), "day", note.day(),
                "content", content.message())).toJson();
    }

    /** 忘掉一条。整理是她自己的事,我们不替她删,也不替她留。 */
    public String forget(UUID companion, String name) {
        boolean gone = NoteBook.of(companion).forget(name);
        return (gone ? TaskResult.ok("forgotten") : TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.NOT_FOUND,
                "no note named " + name, null)).toJson();
    }
}
