package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.Belongings;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code ftbquests.quest.submit}:替她按下任务书上的提交按钮。
 *
 * <p>按钮背后是 FTB 的一条服务端处理({@code SubmitTaskMessage.handle}):取提交者所在队伍的进度、确认没被锁定、
 * 确认这个任务能开始,然后以提交者为上下文调 {@link Task#submitTask}。这里走的就是这一条,判定和扣背包都由
 * FTB 自己做——要几个、认哪些物品、扣哪一格、够不够,一概不在这里算。在它前面多做的只有把"为什么不行"说出来:
 * 按钮那条路遇到这些情况是默不作声。
 *
 * <p>一个任务里要按按钮的条件({@link TaskRole#SUBMIT})按顺序逐个交,就像玩家逐个点;其余条件不交,交回的值里
 * 说它们归谁。观察条件的判定在玩家自己的客户端上(准星停没停在目标上),服务端直接交等于跳过这个判定,不代交。
 *
 * <p>身上变了什么如实回报:交之前记下她的背包与经验,交完比一次。任务因此完成时,完成与 FTB 自动发的奖励
 * 由 {@link QuestWatch} 照常作为事件送到,这里不另报。
 */
final class QuestSubmit {

    private QuestSubmit() {}

    static FtbqApi.Submitted submit(NumenPlayer her, String asked) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        Quest quest = file.getQuest(QuestObjectBase.parseCodeString(asked));
        if (quest == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "No quest has the id \"" + asked + "\"; use the id that "
                    + "ftbquests.quest.list or ftbquests.quest.show prints.", Call.of("ftbquests.quest.list"));
        }
        Optional<TeamData> maybe = file.getTeamData(her);
        if (maybe.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "FTB Quests has no team progress for you, so nothing can be handed in.",
                    null);
        }
        TeamData team = maybe.get();
        String named = "\"" + quest.getTitle().getString() + "\" (" + quest.getCodeString() + ")";
        String forTeam = "your team \"" + team.getName() + "\"";
        String show = Call.of("ftbquests.quest.show", quest.getCodeString());
        if (team.isLocked()) {
            throw new ApiError(ErrorKind.DENIED, "The quest progress of " + forTeam + " is locked, so nothing can be "
                    + "handed in.", null);
        }
        if (team.isCompleted(quest)) {
            throw new ApiError(ErrorKind.FAILED, named + " is already completed for " + forTeam + ".", null);
        }
        if (!team.canStartTasks(quest)) {
            throw new ApiError(ErrorKind.FAILED, named + " cannot be started yet for " + forTeam + ": "
                    + team.getCannotStartReason(quest).getString(), show);
        }

        List<Task> toHandIn = new ArrayList<>();
        List<FtbqApi.QuestTask> skipped = new ArrayList<>();
        for (Task task : quest.getTasksAsList()) {
            if (team.isCompleted(task)) {
                continue;
            }
            TaskRole role = TaskRole.of(task);
            if (role == TaskRole.SUBMIT) {
                toHandIn.add(task);
            } else {
                skipped.add(new FtbqApi.QuestTask(task.getTitle().getString(), false, Optional.empty(), role));
            }
        }
        if (toHandIn.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "Nothing in " + named + " is handed in by submitting.", show,
                    new FtbqApi.Submitted(quest.getCodeString(), List.of(), skipped, "", false));
        }

        Belongings before = Belongings.of(her);
        boolean moved = false;
        List<FtbqApi.Handed> handed = new ArrayList<>();
        for (Task task : toHandIn) {
            long was = team.getProgress(task);
            file.withPlayerContext(her, () -> task.submitTask(team, her));
            long now = team.getProgress(task);
            moved |= now != was;
            handed.add(new FtbqApi.Handed(task.getTitle().getString(), task.formatProgress(team, was),
                    task.formatProgress(team, now) + "/" + task.formatMaxProgress(), team.isCompleted(task)));
        }
        FtbqApi.Submitted done = new FtbqApi.Submitted(quest.getCodeString(), handed, skipped, before.changeTo(her),
                team.isCompleted(quest));
        if (!moved) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "Nothing was handed in for " + named + " — " + forTeam
                    + ": nothing you carry counts.", null, done);
        }
        return done;
    }
}
