package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.task.TaskResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tool implementations — the business half of
 * {@code skill.load} ({@code SkillCommands}) and {@code memory} ({@code MemoryCommands}). These run on
 * the agent thread with no server body and return their result directly.
 */
public final class AgentOps {

    /**
     * 技能正文或它的附属文件,按输出预算一页一页给(一行一条,{@link Listing});正文成型交给 {@link SkillInjection},和主人
     * 打斜杠命令同一个样子。
     *
     */
    public String loadSkill(String name, String file, CommandArgs args) {
        SkillRegistry registry = SkillRegistry.instance();
        if (file != null && !file.isBlank()) {
            // 三级披露:正文引用的附属文件按需拉取
            try {
                String text = registry.readSupportFile(name, file);
                return page(SkillInjection.supportFile(name, file, text), args);
            } catch (IllegalArgumentException ex) {
                return TaskResult.fail(ex.getMessage()).toJson();
            }
        }
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream().map(SkillInfo::name).collect(Collectors.joining(", "));
            return TaskResult.fail("unknown skill: " + name + "; the skills are: "
                    + (available.isEmpty() ? "(none installed)" : available)).toJson();
        }

        // 成型交给 SkillInjection:主人打斜杠命令走的是另一条路,进上下文的东西必须一样。
        return page(SkillInjection.body(maybe.get(), null), args);
    }

    /** 一段文字按输出预算取这一页,一行一条;要的那一页不存在是一条失败。 */
    private static String page(String text, CommandArgs args) {
        return new Listing("", List.of(text.split("\n", -1)), "").result(args).toJson();
    }


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
            return TaskResult.fail("no note named " + name + "; your notes are: "
                    + (known.isEmpty() ? "(none)" : known)).toJson();
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
        return (gone ? TaskResult.ok("forgotten") : TaskResult.fail("no note named " + name)).toJson();
    }
}
