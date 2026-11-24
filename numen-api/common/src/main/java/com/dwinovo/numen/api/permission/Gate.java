package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Merge;
import com.dwinovo.numen.permission.Say;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次裁决用的快照:主人给这只同伴定的设置({@link Settings})、她身边的方块归属、主人答应下来的任务期授权、此刻登记着的回答者。
 * 主线程建({@link Permission#gateFor}),之后任何线程只读——寻路工作线程拿着它给每条边定价。
 *
 * <p>一件事怎么裁:把登记着的回答者问一圈,照 {@link Merge} 的固定顺序合出结果(不许 → 主人的名单 → 要问 → 许 → 这种动作的档位)。
 * 这里不认方块也不认实体,懂东西的只有回答者。要问的动作若已被任务期授权({@link ConsentItem#covers})覆盖就是放行,授权只覆盖问,
 * 解不开不许。
 *
 * <p>两种问法,由调用方按所在线程选:{@link #judge} 只读给定的视图与这份快照,任何线程可调;{@link #judgeLive} 主线程专用,
 * 回答者可以读活世界。
 */
public final class Gate {

    private final Actor actor;
    private final Settings settings;
    private final Owners.Blocks owners;
    private final List<ConsentItem> granted;
    /** 建这份快照时登记着的回答者,按登记先后。 */
    private final Map<String, Answerer> answerers;

    /**
     * @param actor   要动手的同伴是谁、她的名字、她的主人与主人名下别的同伴;测试可传 {@link Actor#NOBODY}
     * @param settings 主人给这只同伴定的档位与名单
     * @param owners  她身边的方块归属({@link Owners#around});没人回答方块归属时是 {@link Owners.Blocks#NONE}
     * @param granted 主人答应下来的任务期授权({@link ConsentDesk#granted})
     */
    public Gate(Actor actor, Settings settings, Owners.Blocks owners, List<ConsentItem> granted) {
        this(actor, settings, owners, granted, Questions.PERMIT.answerers());
    }

    /** 同上,回答者由调用方指定(测试用)。 */
    public Gate(Actor actor, Settings settings, Owners.Blocks owners, List<ConsentItem> granted,
                Map<String, Answerer> answerers) {
        this.actor = actor;
        this.settings = settings;
        this.owners = owners;
        this.granted = List.copyOf(granted);
        this.answerers = new LinkedHashMap<>(answerers);
    }

    public Settings settings() {
        return settings;
    }

    /** 任何线程:只读 {@code view}(活世界或搜索快照)与这份快照裁决一个动作。 */
    public Verdict judge(Action action, BlockGetter view) {
        return decide(action, facts(view, null));
    }

    /** 主线程:对活世界裁决一个动作,回答者可以读活世界。真要动手前的那一问。 */
    public Verdict judgeLive(Action action, ServerLevel level) {
        return decide(action, facts(level, level));
    }

    /** 任何线程:把 {@link #judge} 问出来的动作写成一条征询。 */
    public ConsentItem consentItem(Action action, Verdict verdict) {
        return ConsentItem.of(action, verdict);
    }

    private Facts facts(BlockGetter view, ServerLevel live) {
        return new Facts(view, live, actor, owners);
    }

    /** 一个回答者的一条回答,连同合并要看的话。 */
    private record Voice(Say say, Answer answer) {}

    private Verdict decide(Action action, Facts facts) {
        List<Voice> voices = new ArrayList<>(2);
        for (var entry : answerers.entrySet()) {
            Answer answer;
            try {
                answer = entry.getValue().answer(action, facts);
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; its say is left out this time",
                        entry.getKey(), Questions.PERMIT.id(), e);
                continue;
            }
            if (answer.say() != Say.ABSTAIN) {
                voices.add(new Voice(answer.say(), answer));
            }
        }
        Tier tier = settings.tier(action.kind());
        boolean listed = action.names().stream().anyMatch(name -> settings.listed(action.kind(), name));
        Merge.Result<Voice> result = Merge.merge(tier, listed, voices, Voice::say);
        Verdict verdict = switch (result.outcome()) {
            case ALLOW -> Verdict.allow(result.step());
            case DENY -> result.by() != null
                    ? new Verdict(Tier.DENY, result.step(), result.by().answer().reason(), result.by().answer().shown())
                    : new Verdict(Tier.DENY, result.step(), "the owner's setting for " + action.kind().word() + " is deny",
                            Component.translatable(ModLanguageData.Keys.PERMISSION_TIER_DENY));
            case ASK -> result.by() != null
                    ? new Verdict(Tier.ASK, result.step(), result.by().answer().reason(), result.by().answer().shown())
                    : new Verdict(Tier.ASK, result.step(), "the owner's setting for " + action.kind().word() + " is ask",
                            Component.translatable(ModLanguageData.Keys.PERMISSION_TIER_ASK));
        };
        if (verdict.asks() && granted.stream().anyMatch(g -> g.covers(action))) {
            return Verdict.allow();
        }
        return verdict;
    }
}
