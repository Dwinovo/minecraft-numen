package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.task.TaskResult;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardAutoClaim;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.TextUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一本任务书在一个队伍眼里的样子:{@code list} 与 {@code show} 给她看的文字。
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

    /** 描述超过这么多字就截断:整段剧情塞进上下文不值,要细看的是条件和奖励。 */
    private static final int DESCRIPTION_LIMIT = 600;

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

    /** {@code list} 的清单:能做的任务一行一个,头上说这是谁的书,末尾是钉住的、待领的与怎么看详情。 */
    Listing list() {
        List<Quest> quests = workable();
        List<String> rows = new ArrayList<>();
        for (Quest quest : quests) {
            rows.add("  " + row(quest));
        }
        String head = whose() + "\n" + (quests.isEmpty()
                ? "Nothing to work on right now: every quest in the book is done or still waiting on others."
                : "Quests you can work on now (" + quests.size() + "):");
        String foot = pinnedLine() + "\n" + unclaimedLine() + "\n"
                + FtbqCommands.SHOW + " <quest> shows one quest in full.";
        return new Listing(head, rows, foot, FtbqCommands.LIST);
    }

    /** {@code show}:按编号或标题找一个书里找得到的任务,把它摊开。 */
    String show(String asked) {
        String wanted = asked.strip();
        List<Quest> found = named(wanted);
        if (found.isEmpty()) {
            return TaskResult.fail("No quest in your owner's book has the id or title \"" + wanted + "\". "
                    + FtbqCommands.LIST + " shows the ones you can work on.").toJson();
        }
        if (found.size() > 1) {
            List<String> which = new ArrayList<>();
            for (Quest quest : found) {
                which.add(quest.getCodeString() + " (chapter " + text(quest.getChapter().getTitle()) + ")");
            }
            return TaskResult.fail("Several quests are titled \"" + wanted + "\": " + String.join(", ", which)
                    + ". Name one by its id.").toJson();
        }
        return TaskResult.ok(detail(found.get(0))).toJson();
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

    private String detail(Quest quest) {
        StringBuilder sb = new StringBuilder(quest.getCodeString()).append(" · ").append(text(quest.getTitle()))
                .append(" · chapter ").append(text(quest.getChapter().getTitle()));
        sb.append('\n').append(whose());
        String subtitle = parsed(quest, quest.getRawSubtitle());
        if (!subtitle.isBlank()) {
            sb.append("\nSubtitle: ").append(subtitle);
        }
        boolean canStart = team.canStartTasks(quest);
        sb.append("\nStatus: ").append(team.isCompleted(quest) ? "completed"
                : canStart ? "can be worked on now"
                : "cannot start yet (" + text(team.getCannotStartReason(quest)) + ")");
        if (quest.hasDependencies()) {
            List<String> deps = new ArrayList<>();
            quest.streamDependencies().forEach(dep -> deps.add(dependency(dep)));
            sb.append("\nDepends on: ").append(String.join("; ", deps));
        }
        if (!canStart && quest.hideDetailsUntilStartable()) {
            return sb.append("\nThe book keeps the rest of this quest hidden until it can be started.").toString();
        }
        sb.append('\n').append(description(quest));
        List<Task> shown = shownTasks(quest);
        sb.append(shown.isEmpty() ? "\nTasks: none." : "\nTasks:");
        for (Task task : shown) {
            sb.append("\n  ").append(text(task.getTitle()))
                    .append(" — ").append(team.isCompleted(task) ? "done" : progress(task))
                    .append(" — ").append(TaskRole.of(task).label());
        }
        if (shown.size() < quest.getTasks().size()) {
            sb.append("\n  (").append(quest.getTasks().size() - shown.size())
                    .append(" more show up one at a time: this quest's tasks go in order)");
        }
        List<String> rewards = new ArrayList<>();
        for (Reward reward : quest.getRewards()) {
            if (!team.isRewardBlocked(reward) && reward.getAutoClaimType() != RewardAutoClaim.INVISIBLE) {
                rewards.add(reward(reward));
            }
        }
        if (!rewards.isEmpty()) {
            sb.append("\nRewards:");
            for (String line : rewards) {
                sb.append("\n  ").append(line);
            }
        }
        return sb.toString();
    }

    /** 列表的一行:编号 · 标题 · 章节 · 还差的每个条件(进度,谁来完成)。 */
    private String row(Quest quest) {
        List<String> left = new ArrayList<>();
        for (Task task : shownTasks(quest)) {
            if (!team.isCompleted(task)) {
                left.add(text(task.getTitle()) + " " + progress(task) + " (" + TaskRole.of(task).label() + ")");
            }
        }
        String row = quest.getCodeString() + " · " + text(quest.getTitle()) + " · "
                + text(quest.getChapter().getTitle()) + " · " + String.join("; ", left);
        return pinned.contains(quest.id) ? row + " [pinned]" : row;
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

    private String dependency(QuestObject dep) {
        return text(dep.getTitle()) + " (" + dep.getCodeString() + ", "
                + (team.isCompleted(dep) ? "completed" : "not completed") + ")";
    }

    /** 正文:跳过分页记号与空行,连成一段;太长截断并说明。设了"完成前隐藏正文"的照做。 */
    private String description(Quest quest) {
        boolean hidden = quest.getHideTextUntilComplete().get(quest.getChapter().isHideTextUntilComplete())
                && !team.isCompleted(quest);
        if (hidden) {
            return "Description: hidden in the book until the quest is completed.";
        }
        List<String> kept = new ArrayList<>();
        for (String raw : quest.getRawDescription()) {
            String line = raw.equals(Quest.PAGEBREAK_CODE) ? "" : parsed(quest, raw).strip();
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        if (kept.isEmpty()) {
            return "Description: none.";
        }
        String all = String.join(" ", kept);
        return all.length() <= DESCRIPTION_LIMIT
                ? "Description: " + all
                : "Description: " + all.substring(0, DESCRIPTION_LIMIT) + "… (cut, " + all.length() + " characters in all)";
    }

    /** 一个奖励:个人还是队伍的、自动领还是要在书里点、领了没有。 */
    private String reward(Reward reward) {
        String how = reward.getAutoClaimType() == RewardAutoClaim.DISABLED
                ? "claimed by hand in the book" : "claimed automatically";
        String claimed;
        if (reward.isTeamReward()) {
            claimed = team.isRewardClaimed(owner, reward) ? "the team has claimed it" : "not claimed yet";
        } else {
            claimed = "you: " + (team.isRewardClaimed(her, reward) ? "claimed" : "not claimed")
                    + ", your owner: " + (team.isRewardClaimed(owner, reward) ? "claimed" : "not claimed");
        }
        return text(reward.getTitle()) + " — " + (reward.isTeamReward() ? "team" : "personal") + ", " + how
                + " — " + claimed;
    }

    /** 这是谁的书:队伍名;她不在这个队伍里时先说清她做的不算。 */
    private String whose() {
        String line = "Your owner's quest book, team \"" + team.getName() + "\".";
        return herInTeam ? line + " You are in this team."
                : line + " You are NOT in this team, so what you do does not count toward these quests.";
    }

    private String pinnedLine() {
        List<String> titles = new ArrayList<>();
        pinned.forEach((long id) -> {
            Quest quest = file.getQuest(id);
            if (quest != null) {
                titles.add(text(quest.getTitle()) + " (" + quest.getCodeString() + ")");
            }
        });
        return titles.isEmpty() ? "Pinned by your owner: none."
                : "Pinned by your owner: " + String.join(", ", titles) + ".";
    }

    private String unclaimedLine() {
        long count = quests().stream().filter(quest -> team.hasUnclaimedRewards(owner, quest)).count();
        return "Completed quests with rewards your owner has not claimed yet: " + count + ".";
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
