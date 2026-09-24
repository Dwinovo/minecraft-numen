package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.entity.NumenPlayer;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbteams.api.event.PlayerChangedTeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 她所在的队伍完成了什么、FTB 因此把哪些奖励记到了她名下——服务端每刻结束时告诉她。
 *
 * <h2>完成</h2>
 * FTB 自己的 {@link ObjectCompletedEvent}:任务、章节各一条。单人游戏里客户端的任务书也会发这个事件,
 * 只认服务端那份。队伍在线成员里的每个同伴各记一条,刻末发。
 *
 * <h2>奖励:以 FTB 的领奖记录为准</h2>
 * FTB 自动发奖有三处:任务或章节完成那一串调用里、成员登录时的任务书检查、加入队伍时补发队伍已完成任务的奖励;
 * 三处都是先在 {@link TeamData} 里记下"某人在某一毫秒领了某个奖励",再把东西给出去。所以"FTB 给了她什么"
 * 只从这份记录里读:领取时刻落在这一刻之内、记在她名下的,就是这一刻新发给她的。个人奖励的记录写着领取人;
 * 团队奖励的记录只写"这个队伍领过了"——FTB 把它交给当时替之结算的那一个成员,记录里不留名字,
 * 所以照实说是队伍领的。
 *
 * <p>什么东西进了她的背包,另从她身上读:每刻结束记下她背包里每格是什么、几个,以及经验;报奖励时和上一刻结束时
 * (她刚进世界那一刻则是入场时)比。这是她身上在同一刻里实际的变化,不是从奖励配置推算的——物品奖励可能带
 * 随机加量、战利品表每次抽的不一样、背包满了会掉在脚边,推算都会说错。
 *
 * <p>只在这一刻里 FTB 可能替她结算过的时候才去翻记录:她的队伍完成了东西、她刚进世界、她换了队伍。
 * 管理员用命令或编辑器强行改进度不发完成事件,那时发的奖励这里不报——和别的管理命令(比如 {@code /give})一样。
 */
final class QuestWatch {

    /** 在世的每个同伴一本账。 */
    private static final Map<UUID, Ledger> LEDGERS = new HashMap<>();
    /** 这一刻里 FTB 可能替她结算过奖励的同伴。 */
    private static final Set<UUID> TO_CHECK = new HashSet<>();
    /** 这一刻里她的队伍完成的任务与章节,按发生顺序。 */
    private static final Map<UUID, List<Completion>> COMPLETED = new HashMap<>();

    private QuestWatch() {}

    private record Completion(String object, String id, String title, String team, ServerPlayer by) {}

    static EventResult onCompleted(ObjectCompletedEvent<?> event) {
        TeamData data = event.getData();
        if (!data.getFile().isServerSide()) {
            return EventResult.pass();
        }
        QuestObject object = event.getObject();
        Completion completion = new Completion(object instanceof Chapter ? "chapter" : "quest",
                object.getCodeString(), object.getTitle().getString(), data.getName(),
                ServerQuestFile.INSTANCE.getCurrentPlayer());
        for (ServerPlayer member : event.getOnlineMembers()) {
            if (member instanceof NumenPlayer her) {
                COMPLETED.computeIfAbsent(her.getUUID(), k -> new ArrayList<>()).add(completion);
                TO_CHECK.add(her.getUUID());
            }
        }
        return EventResult.pass();
    }

    static void onTeamChanged(PlayerChangedTeamEvent event) {
        if (event.getPlayer() instanceof NumenPlayer her) {
            TO_CHECK.add(her.getUUID());
        }
    }

    /**
     * 她进世界(或换维度)。第一次见到她时在这里建账:原版的 {@code placeNewPlayer} 先把她放进世界、
     * 后发登录事件,FTB 的登录检查和它补发的奖励都在登录事件里,所以这一刻记下的是发奖之前的她。
     */
    static void onJoinLevel(Entity entity) {
        if (entity instanceof NumenPlayer her && !LEDGERS.containsKey(her.getUUID())) {
            LEDGERS.put(her.getUUID(), Ledger.of(her, System.currentTimeMillis()));
            TO_CHECK.add(her.getUUID());
        }
    }

    static void onTickEnd(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer her)) {
                continue;
            }
            UUID id = her.getUUID();
            List<Completion> done = COMPLETED.remove(id);
            if (done != null) {
                for (Completion c : done) {
                    FtbqEvents.completed(her, c.object(), c.id(), c.title(), c.team(), c.by());
                }
            }
            Ledger ledger = LEDGERS.get(id);
            if (ledger == null) {
                // 账随身体建、随身体销:这是正在离开世界的那具
                continue;
            }
            Set<Long> toldNow = TO_CHECK.contains(id) ? reportClaims(her, ledger, now) : Set.of();
            ledger.remember(her, now, toldNow);
        }
        // 刻中途离开的同伴:她们的那几条随身体一起作废
        COMPLETED.clear();
        TO_CHECK.clear();
    }

    /** @return 领取时刻恰好是 {@code now} 这一毫秒、这次已经说过的奖励——下一刻的账从 now 开始,别再说一遍 */
    private static Set<Long> reportClaims(NumenPlayer her, Ledger ledger, long now) {
        BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
        Optional<TeamData> maybe = file.getTeamData(her);
        if (maybe.isEmpty()) {
            return Set.of();
        }
        TeamData data = maybe.get();
        List<String> mine = new ArrayList<>();
        List<String> team = new ArrayList<>();
        Set<Long> toldNow = new HashSet<>();
        file.forAllQuests(quest -> {
            for (Reward reward : quest.getRewards()) {
                Optional<java.util.Date> claimed = data.getRewardClaimTime(her.getUUID(), reward);
                if (claimed.isEmpty() || !ledger.isNew(claimed.get().getTime(), reward.id)) {
                    continue;
                }
                String line = reward.getTitle().getString() + " (quest \"" + quest.getTitle().getString() + "\")";
                (reward.isTeamReward() ? team : mine).add(line);
                if (claimed.get().getTime() == now) {
                    toldNow.add(reward.id);
                }
            }
        });
        if (!mine.isEmpty() || !team.isEmpty()) {
            FtbqEvents.rewards(her, mine, team, ledger.changeSince(her));
        }
        return toldNow;
    }

    static void forget(UUID companion) {
        LEDGERS.remove(companion);
        TO_CHECK.remove(companion);
        COMPLETED.remove(companion);
    }

    static void clear() {
        LEDGERS.clear();
        TO_CHECK.clear();
        COMPLETED.clear();
    }

    /** 一个同伴的账:上次看她时她身上是什么样,以及从哪一毫秒起的领奖记录还没告诉她。 */
    private static final class Ledger {
        private final Belongings before;
        /** 领取时刻不早于它的记录是新的。 */
        private long since;
        /** 领取时刻恰好是 {@link #since} 那一毫秒、上次已经说过的奖励。 */
        private Set<Long> toldAtSince = Set.of();

        private Ledger(Belongings before, long since) {
            this.before = before;
            this.since = since;
        }

        static Ledger of(NumenPlayer her, long now) {
            Belongings before = new Belongings(her.getInventory().getContainerSize());
            before.copyFrom(her);
            return new Ledger(before, now);
        }

        boolean isNew(long claimedAt, long rewardId) {
            return claimedAt > since || claimedAt == since && !toldAtSince.contains(rewardId);
        }

        String changeSince(NumenPlayer her) {
            return before.changeTo(her);
        }

        void remember(NumenPlayer her, long now, Set<Long> toldNow) {
            before.copyFrom(her);
            since = now;
            toldAtSince = toldNow;
        }
    }
}
