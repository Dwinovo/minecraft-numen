package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.ClientCall;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import java.util.List;
import java.util.Optional;

/**
 * {@code ftbquests.quest}:她自己点不了的任务书与组队按钮,在这里有一个入口。
 *
 * <p>读任务书({@code list}、{@code show})在主人的客户端上执行,见 {@link ClientBook};提交任务({@code submit})与接受邀请
 * ({@code join})在服务端执行,动的是她的背包与队伍。
 */
public final class FtbqApi {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "ftbquests";

    private FtbqApi() {}

    static void install(NumenApi numen) {
        numen.api("quest", "FTB Quests: your owner's quest book, handing in quests, accepting a party invitation.",
                FtbqApi.class);
    }

    /** 任务的一个条件。 */
    @Doc("One task of a quest.")
    public record QuestTask(String title,
                            boolean done,
                            @Doc("3/10; none for a task that is only done or not.") Optional<String> progress,
                            @Doc("Who does it: counts = you can do it too, submit = hand in with "
                                    + "ftbquests.quest.submit, crafted = only items at the moment they are crafted, "
                                    + "observe = observation (not for you), screen = through a task screen block, "
                                    + "external = the modpack's scripts or another mod.") TaskRole role) {}

    /** 列出来的一个任务。 */
    @Doc("A quest you can work on now.")
    public record Workable(@Doc("What ftbquests.quest.show and ftbquests.quest.submit take.") String id,
                          String title,
                          String chapter,
                          @Doc("Pinned by your owner.") boolean pinned,
                          @Doc("The tasks not done yet.") List<QuestTask> left) {}

    /** 一个任务的编号与标题。 */
    @Doc("A quest by id and title.")
    public record QuestRef(@Doc("What ftbquests.quest.show and ftbquests.quest.submit take.") String id,
                           String title) {}

    /** 主人的任务书,此刻能做的。 */
    @Doc("Your owner's quest book: what can be worked on now.")
    public record Book(@Doc("Your owner's team: whose progress this is.") String team,
                       @Doc("Whether you are in it; if not, what you do does not count for this book.") boolean inTeam,
                       @Doc("Every quest you can work on now, in book order.") List<Workable> quests,
                       @Doc("Pinned by your owner.") List<QuestRef> pinned,
                       @Doc("Completed quests with rewards your owner has not claimed yet.") long unclaimed) {}

    @Fn("The quests you can work on now, what each still needs and who can do it.")
    @Example("ftbquests.quest.list()")
    @Example("for _, q in ipairs(ftbquests.quest.list().quests) do print(q.id, q.title) end")
    @Note("Reads your owner's book: their team's progress, in their language. It changes nothing.")
    @Note("in_team says whether you are in that team; if not, what you do does not count for this book.")
    @SeeAlso({"ftbquests.quest.show", "ftbquests.quest.submit"})
    public static Book list(ClientCall call) {
        return ClientBook.read(call.companion()).list();
    }

    /** 一个任务的状态。 */
    public enum Status { COMPLETED, WORKABLE, CANNOT_START }

    /** 一个前置任务。 */
    @Doc("A quest this one depends on.")
    public record Dependency(String id, String title, boolean completed) {}

    /** 一个奖励。 */
    @Doc("A reward of a quest.")
    public record Reward(String title,
                         @Doc("A team reward, or a personal one.") boolean team,
                         @Doc("Claimed automatically, or by hand in the book.") boolean auto,
                         @Doc("A team reward: claimed.") Optional<Boolean> claimed,
                         @Doc("A personal one: you claimed yours.") Optional<Boolean> youClaimed,
                         @Doc("A personal one: your owner claimed theirs.") Optional<Boolean> ownerClaimed) {}

    /** 一个任务摊开。 */
    @Doc("One quest in full.")
    public record Quest(String id,
                        String title,
                        String chapter,
                        @Doc("Your owner's team: whose progress this is.") String team,
                        boolean inTeam,
                        Optional<String> subtitle,
                        Status status,
                        @Doc("Why it cannot start yet.") Optional<String> cannotStart,
                        List<Dependency> dependencies,
                        @Doc("None while the book hides it.") Optional<String> description,
                        @Doc("The tasks the book shows; none while it hides the quest's details until it can start.")
                        Optional<List<QuestTask>> tasks,
                        @Doc("Tasks still to show up one at a time.") Optional<Integer> moreTasks,
                        @Doc("None while the book hides the quest's details until it can start.")
                        Optional<List<Reward>> rewards) {}

    /** 哪一个任务。 */
    public record Show(@Doc("Which quest: its id, or its full title as ftbquests.quest.list prints it.")
                       String quest) {}

    @Fn("One quest in full: description, dependencies, tasks, rewards.")
    @Example("ftbquests.quest.show(\"15CDF6A098B95FDA\")")
    @Example("ftbquests.quest.show(\"Getting Started\")")
    @SeeAlso("ftbquests.quest.submit")
    public static Quest show(ClientCall call, Show args) {
        return ClientBook.read(call.companion()).show(args.quest());
    }

    /** 交哪一个任务。 */
    public record Submit(@Doc("The quest's id, as list and show print it.") String quest) {}

    /** 交了的一个条件。 */
    @Doc("A task handed in.")
    public record Handed(String title,
                         @Doc("Progress before.") String was,
                         @Doc("Progress now, 3/10.") String progress,
                         boolean done) {}

    /** 交了什么。 */
    @Doc("What a hand-in did.")
    public record Submitted(@Doc("Its id.") String quest,
                            @Doc("Every task handed in.") List<Handed> tasks,
                            @Doc("The tasks not handed in by submitting, and who does them.") List<QuestTask> skipped,
                            @Doc("What left your inventory and experience, -3 minecraft:iron_ingot; empty when "
                                    + "nothing did.") String inventoryChange,
                            @Doc("Whether the quest is completed now.") boolean completed) {}

    /**
     * 提交只收编号:它在服务端执行,服务端的任务书是回退语言,主人语言里的标题在那边对不上。编号是 FTB 的对象编号,两侧一样。
     */
    @Fn("Hand in a quest's items, experience or checkmarks from your own inventory.")
    @Example("ftbquests.quest.submit(\"15CDF6A098B95FDA\")")
    @Note("Takes the items from YOUR inventory and they do not come back; FTB decides what counts. It does not ask "
            + "your owner, so hand in only when they want you to.")
    @Note("Observation tasks are not handed in: they are judged on the player's own screen (what the crosshair rests "
            + "on), so your owner has to do them. Completion and rewards arrive as quest_completed and "
            + "quest_reward_auto events.")
    @Note("When nothing you carry counts, it fails with no_material and the hand-in as data.")
    @SeeAlso({"ftbquests.quest.list", "ftbquests.quest.show"})
    public static Submitted submit(ServerCall call, Submit args) {
        return QuestSubmit.submit(call.her(), args.quest());
    }

    /** 接受哪个队伍的邀请。 */
    public record Join(@Doc("Which party's invitation to accept: the party's short name, as the team_invite event "
            + "gives it, e.g. Dwin_Party#1a2b3c4d.")
                       @Omitted("accept your only pending invitation; with several pending, name one")
                       Optional<String> team) {}

    /** 入了的队伍。 */
    @Doc("The party you joined.")
    public record Joined(@Doc("The party's short name.") String party,
                         String name,
                         @Doc("Whether your owner is in it.") boolean ownerInside) {}

    @Fn("Accept a party invitation you have pending.")
    @Example("ftbquests.quest.join()")
    @Example("ftbquests.quest.join({team = \"Dwin_Party#1a2b3c4d\"})")
    @Note("It does not ask your owner: join only when they agree. You cannot join while you are in another party.")
    @Note("Your quest progress merges into the party's (each task keeps the larger count, quests either side "
            + "completed stay completed); from then on what you do counts for it.")
    @SeeAlso("ftbquests.quest.list")
    public static Joined join(ServerCall call, Join args) {
        return PartyJoin.join(call.her(), args.team().orElse(null));
    }
}
