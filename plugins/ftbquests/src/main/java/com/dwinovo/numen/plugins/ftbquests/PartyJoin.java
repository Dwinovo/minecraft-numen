package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.PartyTeam;

import java.util.List;
import java.util.Map;

/**
 * {@code ftbquests.quest.join}:替她点邀请消息里的"接受"。
 *
 * <p>接受哪一个只看 {@link InviteWatch#pending}——告诉她"有人邀请你"的也是那一处。只挂着一个邀请时不必点名;
 * 挂着几个时用 {@code team} 点名那个队伍的短名(FTB 自己的写法,{@code team_invite} 事件的 {@code party}
 * 与 {@code /ftbteams party join} 用的都是它),点的必须是她挂着的邀请之一。入队本身交给 FTB Teams 的
 * {@link PartyTeam#join},也就是 {@code /ftbteams party join} 在认过邀请之后调的那一个:满员、没命了、
 * 已经在别的队伍里,都由它判、由它拒,拒绝的原话照实转给她。不经那条命令,是因为命令的结果只会作为聊天消息
 * 发到她的假连接上,这里读不到;直接调同一个入队,成败就在返回值与异常里。
 *
 * <p>入队后的事都是 FTB 的规则:她原来那一队的任务进度并进这个队伍(每个条件取较大的进度,双方完成过的都算
 * 完成),队伍已完成任务里自动领取的奖励补发给她——补发经 {@link QuestWatch} 照常作为事件送到。
 *
 * <p>只收"有人邀请了她"的队伍:成员表里明确记着她被邀请的。{@code party join} 命令还认"任何人都能加入"的
 * 队伍,那不是邀请,不在这个动作里。
 */
final class PartyJoin {

    private PartyJoin() {}

    /** @param wanted 点名的队伍短名;没点名为 null */
    static FtbqApi.Joined join(NumenPlayer her, String wanted) {
        List<Team> invites = InviteWatch.pending(her.getUUID());
        if (invites.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "No party has a pending invitation for you.", null);
        }
        List<Team> chosen = wanted == null ? invites
                : invites.stream().filter(team -> team.getShortName().equals(wanted)).toList();
        Map<String, Object> pending = Map.of("pending", invites.stream().map(Team::getShortName).toList());
        if (chosen.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "No pending invitation for you is from the party " + wanted
                    + ". Your pending invitations: " + listed(invites) + ".",
                    invites.size() == 1 ? Call.of("ftbquests.quest.join") : null, pending);
        }
        if (chosen.size() > 1) {
            throw new ApiError(ErrorKind.FAILED, "Several parties have invited you: " + listed(chosen)
                    + ". Ask your owner which party to join, then name it with {team = <short name>}.", null, pending);
        }
        Team party = chosen.get(0);
        try {
            ((PartyTeam) party).join(her);
        } catch (CommandSyntaxException e) {
            throw new ApiError(ErrorKind.DENIED, "FTB Teams did not let you join " + named(party) + ": "
                    + e.getMessage(), null);
        }
        boolean ownerInside = her.getOwnerUuid() != null && party.getMembers().contains(her.getOwnerUuid());
        return new FtbqApi.Joined(party.getShortName(), party.getName().getString(), ownerInside);
    }

    private static String listed(List<Team> parties) {
        return String.join(", ", parties.stream().map(PartyJoin::named).toList());
    }

    private static String named(Team party) {
        return "\"" + party.getName().getString() + "\" (" + party.getShortName() + ")";
    }
}
