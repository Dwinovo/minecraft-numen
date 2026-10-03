package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.UUID;

/**
 * 一本任务书在一个队伍眼里的样子:{@code list} 与 {@code show} 给她看的文字,与同样这些事实的数据。
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

    /**
     * {@code list}:能做的任务一行一个,头上说这是谁的书,末尾是钉住的、待领的与怎么看详情;数据是同样这些
     * ({@link FtbqCommands} 里 list 声明的那张表)。
     */
    TaskResult list(CommandArgs args) {
        List<Quest> quests = workable();
        List<String> rows = new ArrayList<>();
        JsonArray listed = new JsonArray();
        for (Quest quest : quests) {
            JsonObject o = row(quest);
            listed.add(o);
            rows.add("  " + rowText(o));
        }
        String head = whose() + "\n" + (quests.isEmpty()
                ? "Nothing to work on right now: every quest in the book is done or still waiting on others."
                : "Quests you can work on now (" + quests.size() + "):");
        JsonArray pinnedQuests = pinnedQuests();
        long unclaimed = unclaimed();
        String foot = pinnedLine(pinnedQuests) + "\n"
                + "Completed quests with rewards your owner has not claimed yet: " + unclaimed + ".\n"
                + FtbqCommands.SHOW + "(<quest id or title>) shows one quest in full.";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", team.getName());
        data.put("in_team", herInTeam);
        data.put("quests", listed);
        data.put("pinned", pinnedQuests);
        data.put("unclaimed", unclaimed);
        return new Listing(head, rows, foot).result(args, data);
    }

    /** {@code show}:按编号或标题找一个书里找得到的任务,把它摊开。 */
    TaskResult show(String asked) {
        String wanted = asked.strip();
        List<Quest> found = named(wanted);
        if (found.isEmpty()) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "No quest in your owner's book has the id or title \""
                    + wanted + "\"; " + FtbqCommands.LIST + " shows the ones you can work on.",
                    FtbqCommands.LIST + "()");
        }
        if (found.size() > 1) {
            List<String> which = new ArrayList<>();
            JsonArray candidates = new JsonArray();
            for (Quest quest : found) {
                which.add(quest.getCodeString() + " (chapter " + text(quest.getChapter().getTitle()) + ")");
                JsonObject o = new JsonObject();
                o.addProperty("id", quest.getCodeString());
                o.addProperty("chapter", text(quest.getChapter().getTitle()));
                candidates.add(o);
            }
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "Several quests are titled \"" + wanted + "\": "
                    + String.join(", ", which) + ". Name one by its id.", null, Map.of("candidates", candidates));
        }
        JsonObject data = new JsonObject();
        return TaskResult.ok(detail(found.get(0), data), data);
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

    /** 一个任务摊开的那段话;同样这些事实写进 {@code data}(show 声明的那张表)。 */
    private String detail(Quest quest, JsonObject data) {
        data.addProperty("id", quest.getCodeString());
        data.addProperty("title", text(quest.getTitle()));
        data.addProperty("chapter", text(quest.getChapter().getTitle()));
        data.addProperty("team", team.getName());
        data.addProperty("in_team", herInTeam);
        StringBuilder sb = new StringBuilder(quest.getCodeString()).append(" · ").append(text(quest.getTitle()))
                .append(" · chapter ").append(text(quest.getChapter().getTitle()));
        sb.append('\n').append(whose());
        String subtitle = parsed(quest, quest.getRawSubtitle());
        if (!subtitle.isBlank()) {
            sb.append("\nSubtitle: ").append(subtitle);
            data.addProperty("subtitle", subtitle);
        }
        boolean canStart = team.canStartTasks(quest);
        if (team.isCompleted(quest)) {
            sb.append("\nStatus: completed");
            data.addProperty("status", "completed");
        } else if (canStart) {
            sb.append("\nStatus: can be worked on now");
            data.addProperty("status", "workable");
        } else {
            String reason = text(team.getCannotStartReason(quest));
            sb.append("\nStatus: cannot start yet (").append(reason).append(")");
            data.addProperty("status", "cannot_start");
            data.addProperty("cannot_start", reason);
        }
        JsonArray dependencies = new JsonArray();
        if (quest.hasDependencies()) {
            List<String> deps = new ArrayList<>();
            quest.streamDependencies().forEach(dep -> {
                boolean done = team.isCompleted(dep);
                deps.add(text(dep.getTitle()) + " (" + dep.getCodeString() + ", "
                        + (done ? "completed" : "not completed") + ")");
                JsonObject o = new JsonObject();
                o.addProperty("id", dep.getCodeString());
                o.addProperty("title", text(dep.getTitle()));
                o.addProperty("completed", done);
                dependencies.add(o);
            });
            sb.append("\nDepends on: ").append(String.join("; ", deps));
        }
        data.add("dependencies", dependencies);
        if (!canStart && quest.hideDetailsUntilStartable()) {
            return sb.append("\nThe book keeps the rest of this quest hidden until it can be started.").toString();
        }
        sb.append('\n').append(description(quest, data));
        List<Task> shown = shownTasks(quest);
        sb.append(shown.isEmpty() ? "\nTasks: none." : "\nTasks:");
        JsonArray tasks = new JsonArray();
        for (Task task : shown) {
            JsonObject o = task(task);
            tasks.add(o);
            sb.append("\n  ").append(text(task.getTitle()))
                    .append(" — ").append(team.isCompleted(task) ? "done" : progress(task))
                    .append(" — ").append(TaskRole.of(task).label());
        }
        data.add("tasks", tasks);
        if (shown.size() < quest.getTasks().size()) {
            int more = quest.getTasks().size() - shown.size();
            sb.append("\n  (").append(more).append(" more show up one at a time: this quest's tasks go in order)");
            data.addProperty("more_tasks", more);
        }
        List<String> rewardLines = new ArrayList<>();
        JsonArray rewards = new JsonArray();
        for (Reward reward : quest.getRewards()) {
            if (!team.isRewardBlocked(reward) && reward.getAutoClaimType() != RewardAutoClaim.INVISIBLE) {
                rewardLines.add(reward(reward, rewards));
            }
        }
        data.add("rewards", rewards);
        if (!rewardLines.isEmpty()) {
            sb.append("\nRewards:");
            for (String line : rewardLines) {
                sb.append("\n  ").append(line);
            }
        }
        return sb.toString();
    }

    /** 列表的一项:编号、标题、章节、钉没钉住、还差的每个条件。 */
    private JsonObject row(Quest quest) {
        JsonObject o = new JsonObject();
        o.addProperty("id", quest.getCodeString());
        o.addProperty("title", text(quest.getTitle()));
        o.addProperty("chapter", text(quest.getChapter().getTitle()));
        o.addProperty("pinned", pinned.contains(quest.id));
        JsonArray left = new JsonArray();
        for (Task task : shownTasks(quest)) {
            if (!team.isCompleted(task)) {
                left.add(task(task));
            }
        }
        o.add("left", left);
        return o;
    }

    /** 列表的一行,从 {@link #row} 那一项写:编号 · 标题 · 章节 · 还差的每个条件(进度,谁来完成)。 */
    private static String rowText(JsonObject row) {
        List<String> left = new ArrayList<>();
        for (var element : row.getAsJsonArray("left")) {
            JsonObject t = element.getAsJsonObject();
            left.add(t.get("title").getAsString() + " " + (t.has("progress") ? t.get("progress").getAsString()
                    : "not done") + " (" + TaskRole.valueOf(t.get("role").getAsString().toUpperCase(Locale.ROOT))
                    .label() + ")");
        }
        String text = row.get("id").getAsString() + " · " + row.get("title").getAsString() + " · "
                + row.get("chapter").getAsString() + " · " + String.join("; ", left);
        return row.get("pinned").getAsBoolean() ? text + " [pinned]" : text;
    }

    /** 一个条件:标题、做完没有、进度(只有"做没做"两态的没有)、谁来完成。 */
    private JsonObject task(Task task) {
        JsonObject o = new JsonObject();
        o.addProperty("title", text(task.getTitle()));
        o.addProperty("done", team.isCompleted(task));
        if (!task.hideProgressNumbers()) {
            o.addProperty("progress", progress(task));
        }
        o.addProperty("role", TaskRole.of(task).word());
        return o;
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
     * 正文:跳过分页记号与空行,连成一段,整段给出——她点名要看的就是这一个任务,正文里常有怎么做的说明;长度随这一个
     * 任务的定义有界。设了"完成前隐藏正文"的照做,数据里就没有 {@code description}。
     */
    private String description(Quest quest, JsonObject data) {
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
        String text = String.join(" ", kept);
        data.addProperty("description", text);
        return kept.isEmpty() ? "Description: none." : "Description: " + text;
    }

    /** 一个奖励:个人还是队伍的、自动领还是要在书里点、领了没有;数据加进 {@code into}。 */
    private String reward(Reward reward, JsonArray into) {
        boolean auto = reward.getAutoClaimType() != RewardAutoClaim.DISABLED;
        JsonObject o = new JsonObject();
        o.addProperty("title", text(reward.getTitle()));
        o.addProperty("team", reward.isTeamReward());
        o.addProperty("auto", auto);
        String claimed;
        if (reward.isTeamReward()) {
            boolean teamClaimed = team.isRewardClaimed(owner, reward);
            o.addProperty("claimed", teamClaimed);
            claimed = teamClaimed ? "the team has claimed it" : "not claimed yet";
        } else {
            boolean you = team.isRewardClaimed(her, reward);
            boolean yours = team.isRewardClaimed(owner, reward);
            o.addProperty("you_claimed", you);
            o.addProperty("owner_claimed", yours);
            claimed = "you: " + (you ? "claimed" : "not claimed")
                    + ", your owner: " + (yours ? "claimed" : "not claimed");
        }
        into.add(o);
        return text(reward.getTitle()) + " — " + (reward.isTeamReward() ? "team" : "personal") + ", "
                + (auto ? "claimed automatically" : "claimed by hand in the book") + " — " + claimed;
    }

    /** 这是谁的书:队伍名;她不在这个队伍里时先说清她做的不算。 */
    private String whose() {
        String line = "Your owner's quest book, team \"" + team.getName() + "\".";
        return herInTeam ? line + " You are in this team."
                : line + " You are NOT in this team, so what you do does not count toward these quests.";
    }

    /** 主人钉住的任务:编号与标题。 */
    private JsonArray pinnedQuests() {
        JsonArray out = new JsonArray();
        pinned.forEach((long id) -> {
            Quest quest = file.getQuest(id);
            if (quest != null) {
                JsonObject o = new JsonObject();
                o.addProperty("id", quest.getCodeString());
                o.addProperty("title", text(quest.getTitle()));
                out.add(o);
            }
        });
        return out;
    }

    private static String pinnedLine(JsonArray pinnedQuests) {
        List<String> titles = new ArrayList<>();
        for (var element : pinnedQuests) {
            JsonObject o = element.getAsJsonObject();
            titles.add(o.get("title").getAsString() + " (" + o.get("id").getAsString() + ")");
        }
        return titles.isEmpty() ? "Pinned by your owner: none."
                : "Pinned by your owner: " + String.join(", ", titles) + ".";
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
