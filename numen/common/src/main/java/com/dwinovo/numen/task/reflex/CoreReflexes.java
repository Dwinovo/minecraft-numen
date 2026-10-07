package com.dwinovo.numen.task.reflex;

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

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.BreathChain());
        ReflexRegistry.register(new com.dwinovo.numen.task.chain.FleeChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new UnstuckChain());
    }
}
