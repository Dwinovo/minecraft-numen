package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FTB 那边发生在她身上、她该知道的三件事,以及每件事拼给模型的样子。
 *
 * <ul>
 *   <li>{@value #QUEST_COMPLETED}:她所在的队伍完成了一个任务或章节。攒着搭车——她不知道也不会做错事,
 *       下次开口自然带上。</li>
 *   <li>{@value #QUEST_REWARD_AUTO}:FTB 自动领取的奖励记到了她名下。东西进了她的背包,
 *       身体上的变化必须让她知道;同样不急。</li>
 *   <li>{@value #TEAM_INVITE}:有个队伍邀请她加入。恒为急件:那边有人在等她回话,
 *       而她自己点不了邀请消息里的按钮,得马上告诉邀请的人怎么办。</li>
 * </ul>
 */
final class FtbqEvents {

    static final String QUEST_COMPLETED = "quest_completed";
    static final String QUEST_REWARD_AUTO = "quest_reward_auto";
    static final String TEAM_INVITE = "team_invite";

    private static NumenApi numen;

    private FtbqEvents() {}

    /** 登记处那一刻把 API 交过来;发事件要用它。 */
    static void bind(NumenApi api) {
        numen = api;
        api.registerEventType(QUEST_COMPLETED, false);
        api.registerEventType(QUEST_REWARD_AUTO, false);
        api.registerEventType(TEAM_INVITE, true);
    }

    /**
     * 队伍完成了一个任务或章节。
     *
     * @param object "quest" 或 "chapter"
     * @param id     FTB 的对象编号(十六进制那串),任务书里认它
     * @param by     FTB 结算时替谁算的;击杀这类没有玩家上下文的完成是 null
     */
    static void completed(NumenPlayer her, String object, String id, String title, String team, ServerPlayer by) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("object", object);
        attrs.put("id", id);
        String who = "";
        if (by != null) {
            attrs.put("by", by.getGameProfile().getName());
            who = by == her ? " — you finished it" : " — " + by.getGameProfile().getName() + " finished it";
        }
        numen.emit(her, QUEST_COMPLETED, attrs,
                "your FTB team \"" + team + "\" completed the " + object + " \"" + title + "\"" + who, false);
    }

    /**
     * FTB 自动领取、记到她名下的奖励,连同同一刻里她身上实际多了什么。
     *
     * @param mine      个人奖励:FTB 记的是"发给了她"
     * @param team      团队奖励:FTB 只记"这个队伍领过了",不记给了谁
     * @param inventory 同一刻里她背包与经验的变化,已经拼好;没变是空串
     */
    static void rewards(NumenPlayer her, List<String> mine, List<String> team, String inventory) {
        StringBuilder text = new StringBuilder("FTB Quests auto-claimed quest rewards");
        if (!mine.isEmpty()) {
            text.append("; for you: ").append(String.join(", ", mine));
        }
        if (!team.isEmpty()) {
            text.append("; team rewards claimed by your team (FTB gives each one to the single member it credits"
                    + " with the quest): ").append(String.join(", ", team));
        }
        text.append(inventory.isEmpty()
                ? ". Your inventory and experience did not change during that tick."
                : ". During that tick your inventory changed: " + inventory + ".");
        numen.emit(her, QUEST_REWARD_AUTO, null, text.toString(), false);
    }

    /**
     * 一个队伍邀请了她。
     *
     * @param party       队伍的短名,{@code /ftbteams party join} 认的就是它
     * @param ownerInside 她的主人是不是这个队伍的成员
     */
    static void invited(NumenPlayer her, String party, String displayName, boolean ownerInside) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("party", party);
        attrs.put("owner_member", String.valueOf(ownerInside));
        numen.emit(her, TEAM_INVITE, attrs,
                "the FTB Teams party \"" + displayName + "\" invited you to join"
                        + (ownerInside ? " — your owner is in it" : " — your owner is not in it"), true);
    }
}
