package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * FTB Quests 联动——她和主人在同一个 FTB 队伍里一起推进任务线。
 *
 * <p>"FTB 把她当真玩家"不在这里:那是引擎对 Architectury 的回答(api 的
 * {@code ArchitecturyPlayerHooksMixin}),她的击杀、持物、到场由此进了 FTB 自己的判定,算给谁由 FTB 的
 * 队伍规则决定。这个联动做的是另一半:FTB 那边因此发生在她身上的事——队伍完成了任务、奖励发到了她身上、
 * 有人邀请她入队——一件件告诉她。
 *
 * <p>它不是 {@code @Mod} 入口:装没装 FTB Quests 由 {@code Builtin} 那道闸判断,为真才调 {@link #install}。
 * 不在的话这个类一次都不会被加载——它直接引用 FTB 的类。整件事都在服务端。
 */
public final class NumenFtbQuests {

    private NumenFtbQuests() {}

    /** 由 {@code Builtin} 在确认 FTB Quests 在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(numen -> {
            // 事件两侧都要登记(服务端的发出口靠它挡,主人客户端的队列靠它投递),所以直接调,不放进 onClient
            FtbqEvents.bind(numen);

            ObjectCompletedEvent.QUEST.register(QuestWatch::onCompleted);
            ObjectCompletedEvent.CHAPTER.register(QuestWatch::onCompleted);
            TeamEvent.PLAYER_CHANGED.register(QuestWatch::onTeamChanged);
            NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> QuestWatch.onJoinLevel(e.getEntity()));
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
                QuestWatch.onTickEnd(e.getServer());
                InviteWatch.onTickEnd(e.getServer());
            });
            numen.on(CompanionEvent.REMOVE, body -> {
                QuestWatch.forget(body.getUUID());
                InviteWatch.forget(body.getUUID());
            });
            // 两本账都是这个世界的,不作废会跟着进下一个存档
            NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
                QuestWatch.clear();
                InviteWatch.clear();
            });

            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
