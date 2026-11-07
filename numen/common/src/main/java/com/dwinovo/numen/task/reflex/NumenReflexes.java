package com.dwinovo.numen.task.reflex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.NumenContent;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.reflex.Reflex;
import com.dwinovo.numen.api.task.reflex.ReflexRegistry;

import com.dwinovo.numen.task.chain.MLGChain;
import com.dwinovo.numen.task.chain.MobDefenseChain;
import com.dwinovo.numen.task.chain.UnstuckChain;

/**
 * Numen's reflex roster: the five survival chains, which implement
 * {@link Reflex} themselves, registered once at {@code NumenContent.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 */
public final class NumenReflexes {

    private NumenReflexes() {}

    /**
     * 本能替身体做了一件事,告诉她:{@code reflex} 属性写本能在名册里的登记名。不急——身体已经自己应对过了,
     * 这条是让她和翻聊天流的主人看得懂刚才发生了什么,随下一次调模型捎带(类型表里 reflex 是知会档,不为它叫醒她)。
     */
    public static void report(NumenPlayer companion, Reflex reflex, String text) {
        emit(companion, reflex, text, false, List.of());
    }

    /** 同上,事关哪几只实体就带上它们的身份(属性 {@code target}):她接着要对它们做什么,用的是同一套名字。 */
    public static void report(NumenPlayer companion, Reflex reflex, String text, List<String> targets) {
        emit(companion, reflex, text, false, targets);
    }

    /** 本能碰上要她当场决定的险情(例如被封死在水下找不到出口):同一种事件,标成急件,立刻叫醒她。 */
    public static void alert(NumenPlayer companion, Reflex reflex, String text) {
        emit(companion, reflex, text, true, List.of());
    }

    private static void emit(NumenPlayer companion, Reflex reflex, String text, boolean urgent, List<String> targets) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("reflex", reflex.id());
        if (!targets.isEmpty()) {
            attrs.put("target", String.join(", ", targets));
        }
        NumenContent.api().emit(companion, EventTypes.REFLEX, attrs, text, urgent);
    }

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.BreathChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.FleeChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new UnstuckChain());
    }
}
