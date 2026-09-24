package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;

/**
 * {@code numen ftbquests}:她自己翻不了的任务书,在这里有一个入口。
 *
 * <p>读任务书({@code list}、{@code show})在主人的客户端上执行,见 {@link ClientBook}。都是长尾,不提升为
 * 快捷工具——装了 FTB 的世界里也不是每几轮就用一次,常驻工具表不值。
 */
final class FtbqCommands {

    static final String GROUP = "ftbquests";
    static final String LIST = NumenCli.ROOT + " " + GROUP + " list";
    static final String SHOW = NumenCli.ROOT + " " + GROUP + " show";

    private static final Param<Integer> PAGE = Param.optional("page", ArgType.integer(1, 99),
            "Which page of the list.");
    /** 客户端按主人的语言认标题,所以 show 编号、标题都收;标题可以带空格,吃掉余下整行。 */
    private static final Param<String> QUEST_NAMED = Param.required("quest", ArgType.text(),
            "A quest's id, or its full title as list prints it.");

    private FtbqCommands() {}

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP,
                "FTB Quests: your owner's quest book.",
                FtbqCommands::actions);
    }

    private static void actions(CommandGroup quests) {
        quests.client("list", "The quests you can work on now, what each still needs and who can do it.",
                (src, args) -> {
                    Integer page = args.get(PAGE);
                    ClientBook.list(src, page == null ? 1 : page);
                }, PAGE);
        quests.client("show", "One quest in full: description, dependencies, tasks, rewards.",
                (src, args) -> ClientBook.show(src, args.get(QUEST_NAMED)), QUEST_NAMED);
    }
}
