package com.dwinovo.numen.task.reflex;

import java.util.Map;

import com.dwinovo.numen.NumenCore;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.reflex.Reflex;
import com.dwinovo.numen.api.task.reflex.ReflexRegistry;

import com.dwinovo.numen.task.chain.MLGChain;
import com.dwinovo.numen.task.chain.MobDefenseChain;
import com.dwinovo.numen.task.chain.UnstuckChain;

/**
 * Numen's reflex roster: the five survival chains, which implement
 * {@link Reflex} themselves, registered once at {@code NumenCore.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 */
public final class CoreReflexes {

    private CoreReflexes() {}

    /**
     * 本能替身体做了一件事,告诉她:{@code reflex} 属性写本能在名册里的登记名。永远不急——身体已经自己应对过了,
     * 这条是让她和翻聊天流的主人看得懂刚才发生了什么,攒着搭下一轮的车就够。
     */
    public static void report(NumenPlayer companion, Reflex reflex, String text) {
        NumenCore.api().emit(companion, EventTypes.REFLEX, Map.of("reflex", reflex.id()), text, false);
    }

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.BreathChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.FleeChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new UnstuckChain());
    }
}
