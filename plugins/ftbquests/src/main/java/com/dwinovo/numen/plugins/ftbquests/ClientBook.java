package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.FTBQuestsClient;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * {@code ftbquests.quest.list|show} 读的那本书,在主人的客户端上。整个联动里只有这个类碰 FTB 的客户端一侧:
 * 客户端任务书 {@link ClientQuestFile}、客户端队伍表、客户端玩家。
 *
 * <p>为什么在客户端读:任务的标题与描述经 FTB 按<b>主人的语言</b>解析({@code {翻译键}}、各语言的任务书译文),
 * 服务端那本永远是回退语言。书里的进度是主人所在队伍的,和主人在任务书界面上看到的是同一份。
 *
 * <p>专用服务器上这个类不会被加载:只有客户端函数真被执行时才碰到它。
 */
final class ClientBook {

    private ClientBook() {}

    /** 翻开主人的任务书,替她({@code companion})读;书还没从服务端同步过来时如实失败。 */
    static QuestBook read(UUID companion) {
        if (!ClientQuestFile.exists() || !FTBTeamsAPI.api().isClientManagerLoaded()) {
            throw new ApiError(ErrorKind.FAILED, "Your owner's quest book has not loaded on their client yet "
                    + "(FTB Quests sends it when they join the world).", null);
        }
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        TeamData team = file.selfTeamData;
        Player owner = FTBQuestsClient.getClientPlayer();
        boolean herInTeam = FTBTeamsAPI.api().getClientManager().selfTeam().getMembers().contains(companion);
        return new QuestBook(file, team, owner.getUUID(), companion, herInTeam, team.getPinnedQuestIds(owner));
    }
}
