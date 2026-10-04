package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.sdk.Call;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardAutoClaim;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.TextUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一本任务书在一个队伍眼里的样子:{@code list} 与 {@code show} 交回的值。
 *
 * <p>读的是主人客户端上的那本({@link ClientBook} 交进来):标题、描述要按主人的语言解析,只有客户端做得到;
 * 进度是主人所在队伍的。这里只认 {@link BaseQuestFile} 与 {@link TeamData},不碰任何客户端类——
 * 同一套判断对服务端那本书一样成立。
 *
 * <p>每个判断都用 FTB 自己的:看不看得见是 {@link Quest#isSearchable}(章节不是永久隐藏、任务本身可见,
 * 也就是任务书里找得到),能不能开始是 {@link TeamData#canStartTasks},进度与格式是 {@link Task} 自己的。
 * 任务书界面藏起来的东西这里也不说:依次完成的条件只露到第一个没完成的,"开始前隐藏详情""完成前隐藏正文"
 * 照做,被封锁或设成不可见的奖励不列。
 */
final class QuestBook {

    private final BaseQuestFile file;
    private final TeamData team;
    private final UUID owner;
    private final UUID her;
    private final boolean herInTeam;
    private final LongSet pinned;

    /**
     * @param team      读这本书的队伍的进度:主人所在的队伍
     * @param owner     主人:钉住的、待领的奖励都是他的
     * @param her       她:个人奖励她领没领
     * @param herInTeam 她是不是这个队伍的成员——不是的话她做的不算,要先说清
     * @param pinned    主人在书里钉住的任务
     */
    QuestBook(BaseQuestFile file, TeamData team, UUID owner, UUID her, boolean herInTeam, LongSet pinned) {
        this.file = file;
        this.team = team;
        this.owner = owner;
        this.her = her;
        this.herInTeam = herInTeam;
        this.pinned = pinned;
    }

    /** 此刻能做的任务:书里找得到、没完成、能开始。按章节、章节内的顺序。 */
    List<Quest> workable() {
        return quests().stream()
                .filter(quest -> quest.isSearchable(team) && !team.isCompleted(quest) && team.canStartTasks(quest))
                .toList();
    }

    /** {@code list}:能做的任务,这是谁的书、钉住的、待领的。 */
    FtbqApi.Book list() {
        List<FtbqApi.Workable> listed = new ArrayList<>();
        for (Quest quest : workable()) {
            listed.add(row(quest));
        }
        return new FtbqApi.Book(team.getName(), herInTeam, listed, pinnedQuests(), unclaimed());
    }

    /** {@code show}:按编号或标题找一个书里找得到的任务,把它摊开。 */
    FtbqApi.Quest show(String asked) {
        String wanted = asked.strip();
        List<Quest> found = named(wanted);
        if (found.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "No quest in your owner's book has the id or title \""
                    + wanted + "\"; ftbquests.quest.list shows the ones you can work on.",
                    Call.of("ftbquests.quest.list"));
        }
        if (found.size() > 1) {
            List<String> which = new ArrayList<>();
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Quest quest : found) {
                which.add(quest.getCodeString() + " (chapter " + text(quest.getChapter().getTitle()) + ")");
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", quest.getCodeString());
                o.put("chapter", text(quest.getChapter().getTitle()));
                candidates.add(o);
            }
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "Several quests are titled \"" + wanted + "\": "
                    + String.join(", ", which) + ". Name one by its id.", null, Map.of("candidates", candidates));
        }
        return detail(found.get(0));
    }

    /** 编号认 FTB 的十六进制编号;不是编号的按标题整句比(不分大小写)。只在书里找得到的任务里找。 */
    private List<Quest> named(String wanted) {
        Quest byId = file.getQuest(QuestObjectBase.parseCodeString(wanted));
        if (byId != null && byId.isSearchable(team)) {
            return List.of(byId);
        }
        String title = wanted.toLowerCase(Locale.ROOT);
        return quests().stream()
                .filter(quest -> quest.isSearchable(team)
                        && text(quest.getTitle()).toLowerCase(Locale.ROOT).equals(title))
                .toList();
    }

    /** 一个任务摊开:书把详情藏到能开始的,只给到前置任务为止。 */
    private FtbqApi.Quest detail(Quest quest) {
        String subtitle = parsed(quest, quest.getRawSubtitle());
        boolean canStart = team.canStartTasks(quest);
        FtbqApi.Status status = team.isCompleted(quest) ? FtbqApi.Status.COMPLETED
                : canStart ? FtbqApi.Status.WORKABLE : FtbqApi.Status.CANNOT_START;
        Optional<String> cannotStart = status == FtbqApi.Status.CANNOT_START
                ? Optional.of(text(team.getCannotStartReason(quest))) : Optional.empty();
        List<FtbqApi.Dependency> dependencies = new ArrayList<>();
        if (quest.hasDependencies()) {
            quest.streamDependencies().forEach(dep -> dependencies.add(new FtbqApi.Dependency(dep.getCodeString(),
                    text(dep.getTitle()), team.isCompleted(dep))));
        }
        boolean hidden = !canStart && quest.hideDetailsUntilStartable();
        List<FtbqApi.QuestTask> tasks = new ArrayList<>();
        List<Task> shown = shownTasks(quest);
        shown.forEach(task -> tasks.add(task(task)));
        int more = quest.getTasks().size() - shown.size();
        List<FtbqApi.Reward> rewards = new ArrayList<>();
        for (Reward reward : quest.getRewards()) {
            if (!team.isRewardBlocked(reward) && reward.getAutoClaimType() != RewardAutoClaim.INVISIBLE) {
                rewards.add(reward(reward));
            }
        }
        return new FtbqApi.Quest(quest.getCodeString(), text(quest.getTitle()), text(quest.getChapter().getTitle()),
                team.getName(), herInTeam, subtitle.isBlank() ? Optional.empty() : Optional.of(subtitle), status,
                cannotStart, dependencies, hidden ? Optional.empty() : description(quest),
                hidden ? Optional.empty() : Optional.of(tasks),
                hidden || more <= 0 ? Optional.empty() : Optional.of(more),
                hidden ? Optional.empty() : Optional.of(rewards));
    }

    /** 列表的一项:编号、标题、章节、钉没钉住、还差的每个条件。 */
    private FtbqApi.Workable row(Quest quest) {
        List<FtbqApi.QuestTask> left = new ArrayList<>();
        for (Task task : shownTasks(quest)) {
            if (!team.isCompleted(task)) {
                left.add(task(task));
            }
        }
        return new FtbqApi.Workable(quest.getCodeString(), text(quest.getTitle()), text(quest.getChapter().getTitle()),
                pinned.contains(quest.id), left);
    }

    /** 一个条件:标题、做完没有、进度(只有"做没做"两态的没有)、谁来完成。 */
    private FtbqApi.QuestTask task(Task task) {
        return new FtbqApi.QuestTask(text(task.getTitle()), team.isCompleted(task),
                task.hideProgressNumbers() ? Optional.empty() : Optional.of(progress(task)), TaskRole.of(task));
    }

    /** 任务书界面露出来的条件:依次完成的任务只露到第一个没完成的(含),其余全露。 */
    private List<Task> shownTasks(Quest quest) {
        List<Task> tasks = quest.getTasksAsList();
        if (!quest.getRequireSequentialTasks()) {
            return tasks;
        }
        List<Task> out = new ArrayList<>();
        for (Task task : tasks) {
            out.add(task);
            if (!team.isCompleted(task)) {
                break;
            }
        }
        return out;
    }

    /** 进度数,按条件自己的格式;只有"做没做"两态的条件不写数。 */
    private String progress(Task task) {
        if (task.hideProgressNumbers()) {
            return "not done";
        }
        return task.formatProgress(team, team.getProgress(task)) + "/" + task.formatMaxProgress();
    }

    /**
     * 正文:跳过分页记号与空行,连成一段,整段给出——她点名要看的就是这一个任务,正文里常有怎么做的说明;长度随这一个任务的定义有界。
     * 设了"完成前隐藏正文"的照做,没有正文。
     */
    private Optional<String> description(Quest quest) {
        boolean hidden = quest.getHideTextUntilComplete().get(quest.getChapter().isHideTextUntilComplete())
                && !team.isCompleted(quest);
        if (hidden) {
            return Optional.empty();
        }
        List<String> kept = new ArrayList<>();
        for (String raw : quest.getRawDescription()) {
            String line = raw.equals(Quest.PAGEBREAK_CODE) ? "" : parsed(quest, raw).strip();
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return Optional.of(String.join(" ", kept));
    }

    /** 一个奖励:个人还是队伍的、自动领还是要在书里点、领了没有。 */
    private FtbqApi.Reward reward(Reward reward) {
        boolean auto = reward.getAutoClaimType() != RewardAutoClaim.DISABLED;
        if (reward.isTeamReward()) {
            return new FtbqApi.Reward(text(reward.getTitle()), true, auto,
                    Optional.of(team.isRewardClaimed(owner, reward)), Optional.empty(), Optional.empty());
        }
        return new FtbqApi.Reward(text(reward.getTitle()), false, auto, Optional.empty(),
                Optional.of(team.isRewardClaimed(her, reward)), Optional.of(team.isRewardClaimed(owner, reward)));
    }

    /** 主人钉住的任务:编号与标题。 */
    private List<FtbqApi.QuestRef> pinnedQuests() {
        List<FtbqApi.QuestRef> out = new ArrayList<>();
        pinned.forEach((long id) -> {
            Quest quest = file.getQuest(id);
            if (quest != null) {
                out.add(new FtbqApi.QuestRef(quest.getCodeString(), text(quest.getTitle())));
            }
        });
        return out;
    }

    /** 完成了、主人还有奖励没领的任务有几个。 */
    private long unclaimed() {
        return quests().stream().filter(quest -> team.hasUnclaimedRewards(owner, quest)).count();
    }

    /** 书里的全部任务,按章节、章节内的顺序。 */
    private List<Quest> quests() {
        List<Quest> out = new ArrayList<>();
        file.forAllQuests(out::add);
        return out;
    }

    private static String text(Component component) {
        return component.getString();
    }

    /**
     * 副标题、正文的一行原文按书的语言解析成文字。FTB 自己的 {@code getSubtitle}/{@code getDescription} 只在
     * 客户端存在(标了 OnlyIn,服务端的类里没有这两个方法),解析用的是它们背后的同一个 {@link TextUtils#parseRawText}
     * ——标题的 {@code getTitle} 两侧用的也是它。原文取自这本书的语言,所以主人客户端上读到的是主人的语言。
     */
    private static String parsed(Quest quest, String raw) {
        return TextUtils.parseRawText(raw, quest.holderLookup()).getString();
    }
}
