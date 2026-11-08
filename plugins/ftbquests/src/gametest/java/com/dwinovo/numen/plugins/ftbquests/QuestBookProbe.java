package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.entity.NumenPlayer;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 任务书({@link QuestBook})的读法在主人的客户端上执行,专用服务器的 GameTest 里没有客户端;这里把服务端那本书交给同一个
 * {@link QuestBook},让用例读它摊出来的样子。{@link QuestBook} 在包内,用例在别的包,所以经这一个入口。
 */
public final class QuestBookProbe {

    private QuestBookProbe() {}

    /** 她所在队伍的那本书里,{@code quest}(编号或标题)摊开的样子。 */
    public static FtbqApi.Quest show(NumenPlayer her, String quest) {
        return book(her).show(quest);
    }

    private static QuestBook book(NumenPlayer her) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData team = file.getTeamData(her).orElseThrow();
        return new QuestBook(file, team, her.getUUID(), her.getUUID(), true, new LongOpenHashSet());
    }
}
