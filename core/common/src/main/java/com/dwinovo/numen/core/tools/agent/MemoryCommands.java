package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.ClientSource;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.tools.AgentOps;

import java.util.List;

/**
 * {@code memory}:她自己的札记——记一条、读一条的正文、忘掉一条。
 *
 * <h2>为什么在主人客户端</h2>
 * 札记本落在主人这边({@code NoteBook},跟着同伴的家目录),服务端没有。命令树两侧都登记(帮助要它),处理函数只在
 * 客户端跑。都不提升成快捷工具。
 *
 * <p>记什么、不记什么写在系统提示词的 {@code <memory_rules>} 里,这里只讲参数怎么填——同一份说明不写两处。
 */
public final class MemoryCommands {

    static final String GROUP = "memory";
    static final String REMEMBER = "remember";
    static final String RECALL = "recall";
    static final String FORGET = "forget";

    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Short kebab-case handle for the note, e.g. main-base; writing a name again replaces that note.");
    /** 札记只有三种,索引里按它标出来。 */
    private static final Param<String> TYPE = Param.required("type", ArgType.oneOf("owner", "world", "lesson"),
            "What kind of note it is: owner = the owner's habits and wishes, world = places and things you have "
                    + "seen, lesson = something you tried that did not work.");
    private static final Param<String> DESCRIPTION = Param.required("description", ArgType.string(),
            "The one line you will see in your index: the fact itself, not a label.");
    private static final Param<String> CONTENT = Param.optional("content", ArgType.string(),
            "A longer body, read back with `memory recall`.")
            .whenOmitted("keep just the line, when it already says everything");
    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "The note's name, exactly as <memory> lists it.");

    private static final AgentOps NOTES = new AgentOps();

    private MemoryCommands() {}

    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Your own notes, which outlive this session: write, read back, drop.",
                MemoryCommands::actions);
    }

    private static void actions(CommandGroup memory) {
        memory.client(REMEMBER, "Write one note to your own memory; it comes back to you as a line in <memory>.",
                MemoryCommands::remember, NEW_NAME, TYPE, DESCRIPTION, CONTENT)
                .example(line(REMEMBER) + " main-base world \"main base -340,68,120, door faces east\"")
                .example(line(REMEMBER) + " swamp-route lesson \"the swamp west of base is too deep to cross\" "
                        + "--content \"tried twice on day 12; go around by the north ridge\"")
                .note("It outlives this session. The description IS the index line, and for most notes the whole "
                        + "note: put the fact in it (\"main base -340,68,120\"), not a label (\"about the base\").")
                .note("Use --content only when there is more worth reading later.")
                .seeAlso(line(RECALL), line(FORGET));
        memory.client(RECALL, "Read the body of one of your notes.",
                MemoryCommands::recall, NAME, Listing.PAGE)
                .example(line(RECALL) + " main-base")
                .note("<memory> already carries each note's line; recall only when that line points at more you "
                        + "need.")
                .note("A note says what was true when you wrote it; the world may have moved on.")
                .note("A long body comes a page at a time; its last line says how to get the next.")
                .seeAlso(line(REMEMBER));
        memory.client(FORGET, "Drop one of your notes for good.",
                MemoryCommands::forget, NAME)
                .example(line(FORGET) + " main-base")
                .note("Use it when a note turned out wrong, or after merging several into one. Nothing else ever "
                        + "removes a note.")
                .seeAlso(line(REMEMBER));
    }

    private static void remember(ClientSource src, CommandArgs args) {
        src.reply(NOTES.remember(src.companion(), args.get(NEW_NAME), args.get(DESCRIPTION), args.get(TYPE),
                args.get(CONTENT)));
    }

    private static void recall(ClientSource src, CommandArgs args) {
        src.reply(NOTES.recall(src.companion(), args.get(NAME), args, args.write(line(RECALL), List.of(NAME))));
    }

    private static void forget(ClientSource src, CommandArgs args) {
        src.reply(NOTES.forget(src.companion(), args.get(NAME)));
    }
}
