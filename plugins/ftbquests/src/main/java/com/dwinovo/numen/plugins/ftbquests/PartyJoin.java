package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.PartyTeam;

import java.util.List;

/**
 * {@code ftbquests join}:替她点邀请消息里的"接受"。
 *
 * <p>接受哪一个只看 {@link InviteWatch#pending}——告诉她"有人邀请你"的也是那一处。只挂着一个邀请时不必点名;
 * 挂着几个时用 {@code --team} 点名那个队伍的短名(FTB 自己的写法,{@code team_invite} 事件的 {@code party}
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

    static void join(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        List<Team> invites = InviteWatch.pending(her.getUUID());
        if (invites.isEmpty()) {
            src.reply(TaskResult.fail("No party has a pending invitation for you.").toJson());
            return;
        }
        String wanted = args.get(FtbqCommands.TEAM);
        List<Team> chosen = wanted == null ? invites
                : invites.stream().filter(team -> team.getShortName().equals(wanted)).toList();
        if (chosen.isEmpty()) {
            src.reply(TaskResult.fail("No pending invitation for you is from the party " + wanted
                    + ". Your pending invitations: " + listed(invites) + ".").toJson());
            return;
        }
        if (chosen.size() > 1) {
            src.reply(TaskResult.fail("Several parties have invited you: " + listed(chosen)
                    + ". Ask your owner which party to join, then name it with --team <short name>.").toJson());
            return;
        }
        Team party = chosen.get(0);
        try {
            ((PartyTeam) party).join(her);
        } catch (CommandSyntaxException e) {
            src.reply(TaskResult.fail("FTB Teams did not let you join " + named(party) + ": " + e.getMessage())
                    .toJson());
            return;
        }
        boolean ownerInside = her.getOwnerUuid() != null && party.getMembers().contains(her.getOwnerUuid());
        src.reply(TaskResult.ok("You joined the party " + named(party)
                + (ownerInside ? ", your owner's party." : "; your owner is not in it.")
                + " FTB merged the quest progress you had into the party's: each task keeps the larger count, "
                + "and quests either side completed stay completed. From now on what you do counts for this party.")
                .toJson());
    }

    private static String listed(List<Team> parties) {
        return String.join(", ", parties.stream().map(PartyJoin::named).toList());
    }

    private static String named(Team party) {
        return "\"" + party.getName().getString() + "\" (" + party.getShortName() + ")";
    }
}
