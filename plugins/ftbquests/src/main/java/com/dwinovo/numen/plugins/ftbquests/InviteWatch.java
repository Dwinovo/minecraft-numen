package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.entity.NumenPlayer;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 有队伍邀请她入队时告诉她。
 *
 * <h2>为什么看队伍的成员表</h2>
 * FTB Teams 没有邀请事件。邀请只做两件事:在队伍的成员表里把被邀请人记成 {@link TeamRank#INVITED},
 * 以及给在线的被邀请人发一条带"接受 / 拒绝"按钮的聊天消息。成员表是"她被邀请了"这件事本身——
 * 命令、队伍界面、建队时捎带的邀请都写它,她不在线时发出的邀请也在里面;{@code /ftbteams party join}
 * 认不认她,查的也是它。聊天消息只是它给真人看的界面,不在线就没有,所以不从那里读。
 *
 * <p>每刻结束看一遍她在哪些队伍里是 INVITED,比上一刻多出来的就是新邀请。她进世界时成员表里已经挂着的邀请
 * 也照实说一次:那是一个还没回应的邀请。她入了队、拒绝了或邀请被撤回,这一条从表里消失,以后再邀请会再说。
 */
final class InviteWatch {

    /** 每个同伴:已经告诉过她、还挂在成员表上的邀请(队伍 id)。 */
    private static final Map<UUID, Set<UUID>> TOLD = new HashMap<>();

    private InviteWatch() {}

    static void onTickEnd(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer her)) {
                continue;
            }
            UUID id = her.getUUID();
            Set<UUID> told = TOLD.getOrDefault(id, Set.of());
            List<Team> pending = pending(id);
            for (Team team : pending) {
                if (!told.contains(team.getId())) {
                    FtbqEvents.invited(her, team.getShortName(), team.getName().getString(),
                            her.getOwnerUuid() != null && team.getMembers().contains(her.getOwnerUuid()));
                }
            }
            if (pending.isEmpty()) {
                TOLD.remove(id);
            } else {
                TOLD.put(id, pending.stream().map(Team::getId).collect(Collectors.toSet()));
            }
        }
    }

    /**
     * 她此刻挂着的邀请:成员表里把她记成 {@link TeamRank#INVITED} 的队伍。"有人邀请了她"只从这里读——
     * 刻末告诉她、{@code ftbquests join} 接受哪一个,都问这一处。
     */
    static List<Team> pending(UUID companion) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return List.of();
        }
        List<Team> out = new ArrayList<>();
        for (Team team : FTBTeamsAPI.api().getManager().getTeams()) {
            // getPlayersByRank(NONE) 就是成员表本身;不用 getRankForPlayer——
            // 它对"任何人可加入"的队伍一律答 INVITED,那不是有人邀请了她
            if (team.isPartyTeam() && team.getPlayersByRank(TeamRank.NONE).get(companion) == TeamRank.INVITED) {
                out.add(team);
            }
        }
        return out;
    }

    static void forget(UUID companion) {
        TOLD.remove(companion);
    }

    static void clear() {
        TOLD.clear();
    }
}
