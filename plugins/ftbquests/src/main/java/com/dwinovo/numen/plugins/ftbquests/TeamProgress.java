package com.dwinovo.numen.plugins.ftbquests;

import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 她所在队伍在任务书里的进度。FTB Quests 的进度记在队伍上(不是玩家上):先问 FTB Teams 她在哪个队,再取那个队的进度;
 * 队没有进度记录(没建过)就是空。
 */
final class TeamProgress {

    private TeamProgress() {}

    static Optional<TeamData> of(BaseQuestFile file, ServerPlayer her) {
        return FTBTeamsAPI.api().getManager().getTeamForPlayer(her)
                .map(team -> file.getNullableTeamData(team.getId()));
    }
}
