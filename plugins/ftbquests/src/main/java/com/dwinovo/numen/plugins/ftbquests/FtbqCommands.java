package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;

import java.util.List;

/**
 * {@code ftbquests}:她自己点不了的任务书与组队按钮,在这里有一个入口。
 *
 * <p>读任务书({@code list}、{@code show})在主人的客户端上执行,见 {@link ClientBook};提交任务({@code submit})
 * 与接受邀请({@code join})在服务端执行,动的是她的背包与队伍。每个动作就是脚本里的一个函数
 * ({@code ftbquests.submit("…")}),和别的动作同一个入口。
 */
final class FtbqCommands {

    static final String GROUP = "ftbquests";
    /** 回执与说明里点名这几个动作时写的函数。 */
    static final String LIST = GROUP + ".list";
    static final String SHOW = GROUP + ".show";
    static final String SUBMIT = GROUP + ".submit";

    /** 客户端按主人的语言认标题,所以 show 编号、标题都收;标题可以带空格,吃掉余下整行。 */
    private static final Param<String> QUEST_NAMED = Param.required("quest", ArgType.text(),
            "Which quest.")
            .values("its id, or its full title as " + LIST + " prints it");
    /**
     * submit 只收编号:它在服务端执行,服务端的任务书是回退语言,主人语言里的标题在那边对不上。
     * 编号是 FTB 的对象编号,两侧一样。
     */
    static final Param<String> QUEST_ID = Param.required("quest", ArgType.word(),
            "The quest's id, as list and show print it.");

    /** 短名是 FTB Teams 给队伍起的写法(显示名里的非字母数字换成下划线,再接 {@code #} 与编号前八位)。 */
    static final Param<String> TEAM = Param.optional("team", ArgType.string(),
            "Which party's invitation to accept.")
            .values("the party's short name, as the team_invite event gives it, e.g. Dwin_Party#1a2b3c4d")
            .whenOmitted("accept your only pending invitation; with several pending, name one");

    private FtbqCommands() {}

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP,
                "FTB Quests: your owner's quest book, handing in quests, accepting a party invitation.",
                FtbqCommands::actions);
    }

    /** 任务的一个条件,{@code QuestBook} 写。 */
    private static final ScriptType QUEST_TASK = ScriptType.table(
            ScriptType.field("title", ScriptType.STRING, null),
            ScriptType.field("done", ScriptType.BOOLEAN, null),
            ScriptType.optional("progress", ScriptType.STRING, "3/10; none for a task that is only done or not."),
            ScriptType.field("role", ScriptType.choice(TaskRole.words()), "Who does it: counts = you can do it "
                    + "too, submit = hand in with " + SUBMIT + ", crafted = only items at the moment they are "
                    + "crafted, observe = observation (not for you), screen = through a task screen block, "
                    + "external = the modpack's scripts or another mod."));

    /** 一个任务的编号与标题。 */
    private static final ScriptType QUEST_REF = ScriptType.table(
            ScriptType.field("id", ScriptType.STRING, "What " + SHOW + " and " + SUBMIT + " take."),
            ScriptType.field("title", ScriptType.STRING, null));

    private static void actions(CommandGroup quests) {
        quests.client("list", "The quests you can work on now, what each still needs and who can do it.",
                ClientBook::list, Listing.PAGE)
                .returns(ScriptType.table(
                        ScriptType.field("team", ScriptType.STRING, "Your owner's team: whose progress this is."),
                        ScriptType.field("in_team", ScriptType.BOOLEAN, "Whether you are in it; if not, what you "
                                + "do does not count for this book."),
                        ScriptType.field("quests", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("id", ScriptType.STRING, null),
                                ScriptType.field("title", ScriptType.STRING, null),
                                ScriptType.field("chapter", ScriptType.STRING, null),
                                ScriptType.field("pinned", ScriptType.BOOLEAN, "Pinned by your owner."),
                                ScriptType.field("left", ScriptType.listOf(QUEST_TASK), "The tasks not done yet."))),
                                "Every quest you can work on now, in book order."),
                        ScriptType.field("pinned", ScriptType.listOf(QUEST_REF), "Pinned by your owner."),
                        ScriptType.field("unclaimed", ScriptType.INTEGER, "Completed quests with rewards your owner "
                                + "has not claimed yet.")))
                .example(LIST + "()")
                .example(LIST + "({page = 2})")
                .example("for _, q in ipairs(" + LIST + "().quests) do print(q.id, q.title) end")
                .note("Reads your owner's book: their team's progress, in their language. It changes nothing.")
                .note("The first line says whether you are in that team; if not, what you do does not count "
                        + "for this book.")
                .seeAlso(path("show"), path("submit"));
        quests.client("show", "One quest in full: description, dependencies, tasks, rewards.",
                (src, args) -> ClientBook.show(src, args.get(QUEST_NAMED)), QUEST_NAMED)
                .returns(ScriptType.table(
                        ScriptType.field("id", ScriptType.STRING, null),
                        ScriptType.field("title", ScriptType.STRING, null),
                        ScriptType.field("chapter", ScriptType.STRING, null),
                        ScriptType.field("team", ScriptType.STRING, "Your owner's team: whose progress this is."),
                        ScriptType.field("in_team", ScriptType.BOOLEAN, null),
                        ScriptType.optional("subtitle", ScriptType.STRING, null),
                        ScriptType.field("status", ScriptType.choice(List.of("completed", "workable", "cannot_start")),
                                null),
                        ScriptType.optional("cannot_start", ScriptType.STRING, "Why it cannot start yet."),
                        ScriptType.field("dependencies", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("id", ScriptType.STRING, null),
                                ScriptType.field("title", ScriptType.STRING, null),
                                ScriptType.field("completed", ScriptType.BOOLEAN, null))), null),
                        ScriptType.optional("description", ScriptType.STRING, "None while the book hides it."),
                        ScriptType.optional("tasks", ScriptType.listOf(QUEST_TASK), "The tasks the book shows; none "
                                + "while it hides the quest's details until it can start."),
                        ScriptType.optional("more_tasks", ScriptType.INTEGER, "Tasks still to show up one at a time."),
                        ScriptType.optional("rewards", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("title", ScriptType.STRING, null),
                                ScriptType.field("team", ScriptType.BOOLEAN, "A team reward, or a personal one."),
                                ScriptType.field("auto", ScriptType.BOOLEAN, "Claimed automatically, or by hand in "
                                        + "the book."),
                                ScriptType.optional("claimed", ScriptType.BOOLEAN, "A team reward: claimed."),
                                ScriptType.optional("you_claimed", ScriptType.BOOLEAN, "A personal one: you claimed "
                                        + "yours."),
                                ScriptType.optional("owner_claimed", ScriptType.BOOLEAN, "A personal one: your owner "
                                        + "claimed theirs."))), null)))
                .example(SHOW + "(\"15CDF6A098B95FDA\")")
                .example(SHOW + "(\"Getting Started\")")
                .seeAlso(path("submit"));
        quests.server("submit", "Hand in a quest's items, experience or checkmarks from your own inventory.",
                QuestSubmit::submit, QUEST_ID)
                .returns(ScriptType.table(
                        ScriptType.field("quest", ScriptType.STRING, "Its id."),
                        ScriptType.field("tasks", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("title", ScriptType.STRING, null),
                                ScriptType.field("was", ScriptType.STRING, "Progress before."),
                                ScriptType.field("progress", ScriptType.STRING, "Progress now, 3/10."),
                                ScriptType.field("done", ScriptType.BOOLEAN, null))), "Every task handed in."),
                        ScriptType.field("inventory_change", ScriptType.STRING, "What left your inventory and "
                                + "experience, -3 minecraft:iron_ingot; empty when nothing did."),
                        ScriptType.field("completed", ScriptType.BOOLEAN, "Whether the quest is completed now.")))
                .example(SUBMIT + "(\"15CDF6A098B95FDA\")")
                .note("Takes the items from YOUR inventory and they do not come back; FTB decides what counts. "
                        + "It does not ask your owner, so hand in only when they want you to.")
                .note("Observation tasks are not supported. Completion and rewards arrive as quest_completed "
                        + "and quest_reward_auto events.")
                .seeAlso(path("list"), path("show"));
        quests.server("join", "Accept a party invitation you have pending.", PartyJoin::join, TEAM)
                .returns(ScriptType.table(
                        ScriptType.field("party", ScriptType.STRING, "The party's short name."),
                        ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("owner_inside", ScriptType.BOOLEAN, "Whether your owner is in it.")))
                .example(GROUP + ".join()")
                .example(GROUP + ".join({team = \"Dwin_Party#1a2b3c4d\"})")
                .note("It does not ask your owner: join only when they agree. You cannot join while you are "
                        + "in another party.")
                .note("Your quest progress merges into the party's; from then on what you do counts for it.")
                .seeAlso(path("list"));
    }

    /** 相关动作里点名一个动作:{@code ftbquests list}。 */
    private static String path(String action) {
        return GROUP + " " + action;
    }
}
