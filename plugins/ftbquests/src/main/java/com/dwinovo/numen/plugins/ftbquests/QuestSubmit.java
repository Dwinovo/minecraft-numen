package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code numen ftbquests submit <quest>}:替她按下任务书上的提交按钮。
 *
 * <p>按钮背后是 FTB 的一条服务端处理({@code SubmitTaskMessage.handle}):取提交者所在队伍的进度、确认没被锁定、
 * 确认这个任务能开始,然后以提交者为上下文调 {@link Task#submitTask}。这里走的就是这一条,判定和扣背包都由
 * FTB 自己做——要几个、认哪些物品、扣哪一格、够不够,一概不在这里算。在它前面多做的只有把"为什么不行"说出来:
 * 按钮那条路遇到这些情况是默不作声。
 *
 * <p>一个任务里要按按钮的条件({@link TaskRole#SUBMIT})按顺序逐个交,就像玩家逐个点;其余条件不交,回执里
 * 说它们归谁。观察条件的判定在玩家自己的客户端上(准星停没停在目标上),服务端直接交等于跳过这个判定,不代交。
 *
 * <p>身上变了什么如实回报:交之前记下她的背包与经验,交完比一次。任务因此完成时,完成与 FTB 自动发的奖励
 * 由 {@link QuestWatch} 照常作为事件送到,这里不另报。
 */
final class QuestSubmit {

    private QuestSubmit() {}

    static void submit(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String asked = args.get(FtbqCommands.QUEST_ID);
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        Quest quest = file.getQuest(QuestObjectBase.parseCodeString(asked));
        if (quest == null) {
            src.reply(TaskResult.fail("No quest has the id \"" + asked + "\". Use the id that "
                    + FtbqCommands.LIST + " or " + FtbqCommands.SHOW + " prints.").toJson());
            return;
        }
        Optional<TeamData> maybe = file.getTeamData(her);
        if (maybe.isEmpty()) {
            src.reply(TaskResult.fail("FTB Quests has no team progress for you, so nothing can be handed in.")
                    .toJson());
            return;
        }
        TeamData team = maybe.get();
        String named = "\"" + quest.getTitle().getString() + "\" (" + quest.getCodeString() + ")";
        String forTeam = "your team \"" + team.getName() + "\"";
        if (team.isLocked()) {
            src.reply(TaskResult.fail("The quest progress of " + forTeam + " is locked, so nothing can be handed in.")
                    .toJson());
            return;
        }
        if (team.isCompleted(quest)) {
            src.reply(TaskResult.fail(named + " is already completed for " + forTeam + ".").toJson());
            return;
        }
        if (!team.canStartTasks(quest)) {
            src.reply(TaskResult.fail(named + " cannot be started yet for " + forTeam + ": "
                    + team.getCannotStartReason(quest).getString()).toJson());
            return;
        }

        List<Task> toHandIn = new ArrayList<>();
        List<String> notByYou = new ArrayList<>();
        boolean observation = false;
        for (Task task : quest.getTasksAsList()) {
            if (team.isCompleted(task)) {
                continue;
            }
            TaskRole role = TaskRole.of(task);
            if (role == TaskRole.SUBMIT) {
                toHandIn.add(task);
            } else {
                notByYou.add(task.getTitle().getString() + " (" + role.label() + ")");
                observation |= role == TaskRole.OBSERVE;
            }
        }
        String others = notByYou.isEmpty() ? "" : "\nNot handed in by submit: " + String.join("; ", notByYou) + "."
                + (observation ? " Observation is judged on the player's own screen (what the crosshair rests on);"
                        + " handing it in for you would skip that check, so your owner has to do it." : "");
        if (toHandIn.isEmpty()) {
            src.reply(TaskResult.fail("Nothing in " + named + " is handed in by submitting." + others).toJson());
            return;
        }

        Belongings before = new Belongings(her.getInventory().getContainerSize());
        before.copyFrom(her);
        boolean moved = false;
        StringBuilder lines = new StringBuilder();
        for (Task task : toHandIn) {
            long was = team.getProgress(task);
            file.withPlayerContext(her, () -> task.submitTask(team, her));
            long now = team.getProgress(task);
            moved |= now != was;
            lines.append("\n  ").append(task.getTitle().getString()).append(": ")
                    .append(task.formatProgress(team, was)).append(" -> ").append(task.formatProgress(team, now))
                    .append('/').append(task.formatMaxProgress())
                    .append(team.isCompleted(task) ? " (done)" : now == was ? " (nothing you carry counts)" : "");
        }
        String change = before.changeTo(her);
        String body = lines + others + (change.isEmpty()
                ? "\nYour inventory and experience did not change."
                : "\nYour inventory changed: " + change + ".");
        if (!moved) {
            src.reply(TaskResult.fail("Nothing was handed in for " + named + " — " + forTeam + ":" + body).toJson());
            return;
        }
        String done = team.isCompleted(quest)
                ? "\nThe quest is completed; its completion and any rewards FTB hands out reach you as events." : "";
        src.reply(TaskResult.ok("Handed in for " + named + " — " + forTeam + ":" + body + done).toJson());
    }
}
