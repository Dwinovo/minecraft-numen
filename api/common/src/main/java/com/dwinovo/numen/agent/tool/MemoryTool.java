package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.memory.NoteBook;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 她自己的札记,照 Anthropic 的 memory 工具:一个工具,{@code command} 说这一次做哪件事——记一条、读一条的正文、忘掉一条。札记本
 * ({@link NoteBook})落在主人客户端,管的是大脑自己的事、不碰世界,所以是工具而不是 Lua API;工具当场在主人客户端答,外接大脑(MCP)
 * 经 {@code ToolRegistry} 拿到的是同一个工具。札记的索引照旧每轮作为 {@code <memory>} 注入;记什么、不记什么写在系统提示的
 * {@code <memory_rules>} 里,这里只讲参数怎么填——同一份说明不写两处。
 *
 * <p>为什么是一个工具带 {@code command},不是三个工具:三件事作用于同一本札记,说明与参数大半共用;Anthropic 的 memory 工具与
 * 文本编辑工具都是这样一个工具几种命令,工具表也只多一项。
 */
public final class MemoryTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "memory";

    static final String REMEMBER = "remember";
    static final String RECALL = "recall";
    static final String FORGET = "forget";

    /** 不写 {@code type} 时记成哪一种:多数札记记的是看到的地方与东西。 */
    private static final String DEFAULT_TYPE = "world";
    /** 札记只有三种,索引里按它标出来。 */
    private static final List<String> TYPES = List.of("owner", "world", "lesson");
    /** 不点名的札记用的名字的前缀:{@code note-1}、{@code note-2}…… */
    private static final String UNNAMED = "note-";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 照 Anthropic memory 工具的描述写:动词起头,说清三种命令各做什么
        return "Keeps your own notes, which outlive this session; their index comes to you as <memory> every turn.\n"
                + "- command \"remember\" writes one note: description is the line you will see in <memory>, the fact "
                + "itself (\"main base -340,68,120\"), not a label; name is its handle (writing a name again replaces "
                + "that note); content is a longer body, only when there is more worth reading later.\n"
                + "- command \"recall\" reads the body of one note by its name, as <memory> lists it; a long body comes "
                + "a page at a time and its last line says which page to ask for next.\n"
                + "- command \"forget\" drops one note for good, when it turned out wrong or after merging several "
                + "into one. Nothing else ever removes a note.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Schema.Builder schema = Schema.object();
        schema.enumStr("command", "What to do: remember, recall or forget.", REMEMBER, RECALL, FORGET);
        schema.optionalString("name", "The note's handle, short kebab-case like main-base. remember: omit it to get "
                + "the next free note-1, note-2, …; recall and forget: the name exactly as <memory> lists it.");
        schema.optionalString("description", "remember: the one line you will see in <memory>.");
        schema.optionalEnum("type", "remember: owner = the owner's habits and wishes, world = places and things you "
                + "have seen, lesson = something you tried that did not work. Omit to file it as " + DEFAULT_TYPE
                + ".", TYPES.toArray(String[]::new));
        schema.optionalString("content", "remember: a longer body, read back with recall. Omit to keep just the line.");
        schema.optionalInteger("page", "recall: which page of a long body. Omit for the first.", 1, 99);
        return schema.build();
    }

    @Override
    public void invoke(ToolCall call) {
        JsonObject args = call.args();
        UUID her = call.ctx().entityUuid();
        String command = text(args, "command");
        String result;
        try {
            result = switch (command == null ? "" : command) {
                case REMEMBER -> remember(her, args);
                case RECALL -> recall(her, args);
                case FORGET -> forget(her, args);
                default -> throw new IllegalArgumentException("command is remember, recall or forget; got "
                        + (command == null ? "nothing" : "\"" + command + "\""));
            };
        } catch (IllegalArgumentException bad) {
            result = ToolOutcome.failure(bad.getMessage());
        }
        call.complete(result);
    }

    /**
     * 记一条。天数由札记本盖,结果只在条数到顶时多说一句——删哪条是她的事。不点名时用还没用过的 {@code note-N} 里最小的那个。
     */
    private static String remember(UUID companion, JsonObject args) {
        String description = required(args, "description");
        String type = text(args, "type") == null ? DEFAULT_TYPE : text(args, "type");
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type is one of " + String.join(", ", TYPES) + "; got \"" + type + "\"");
        }
        NoteBook book = NoteBook.of(companion);
        String name = text(args, "name");
        if (name == null) {
            Set<String> taken = new HashSet<>();
            book.index().forEach(n -> taken.add(n.name()));
            int k = 1;
            while (taken.contains(UNNAMED + k)) {
                k++;
            }
            name = UNNAMED + k;
        }
        NoteBook.Note note = book.write(name, description, type, text(args, "content"));
        int count = book.index().size();
        return ToolOutcome.success(count >= NoteBook.SOFT_MAX
                ? "remembered " + note.name() + " — you now keep " + count + " notes; look for ones to merge or forget"
                : "remembered " + note.name() + "; you keep " + count + " notes");
    }

    /** 读一条的正文,按输出预算一页一页给。没有这条就把有的名字列出来——她记的名字自己最清楚,别让她瞎猜。 */
    private static String recall(UUID companion, JsonObject args) {
        String name = required(args, "name");
        NoteBook book = NoteBook.of(companion);
        NoteBook.Note note = book.read(name);
        if (note == null) {
            return missing(book, name);
        }
        JsonElement page = args.get("page");
        String body = new Listing(List.of(note.content().split("\n", -1)))
                .page(page == null || page.isJsonNull() ? 1 : page.getAsInt());
        return ToolOutcome.success(note.name() + " (written on day " + note.day() + "):\n" + body);
    }

    /** 忘掉一条。整理是她自己的事,我们不替她删,也不替她留。 */
    private static String forget(UUID companion, JsonObject args) {
        String name = required(args, "name");
        NoteBook book = NoteBook.of(companion);
        return book.forget(name) ? ToolOutcome.success("forgot " + name) : missing(book, name);
    }

    private static String missing(NoteBook book, String name) {
        String known = book.index().stream().map(NoteBook.Note::name).collect(Collectors.joining(", "));
        return ToolOutcome.failure("no note named " + name + "; your notes are: "
                + (known.isEmpty() ? "(none)" : known));
    }

    private static String required(JsonObject args, String key) {
        String value = text(args, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is missing");
        }
        return value;
    }

    private static String text(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
