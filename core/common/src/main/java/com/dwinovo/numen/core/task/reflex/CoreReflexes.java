package com.dwinovo.numen.core.task.reflex;

import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import com.dwinovo.numen.task.reflex.PolicyReflex;

import com.dwinovo.numen.core.task.chain.FoodChain;
import com.dwinovo.numen.core.task.chain.MLGChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.core.task.chain.UnstuckChain;

/**
 * numen-core's reflex roster: the four survival chains (which implement
 * {@link Reflex} themselves — chain shape untouched) plus one pure policy,
 * registered once at {@code NumenCore.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 */
public final class CoreReflexes {

    /** {@code FoodPolicy} — what the auto-eat chain may pick on its own. */
    public static final String FOOD_POLICY_ID = "food_policy";

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.BreathChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new FoodChain());
        ReflexRegistry.register(new UnstuckChain());
        ReflexRegistry.register(new PolicyReflex(FOOD_POLICY_ID,
                "自己找吃的时会避开有毒或有害的食物"));
    }
}
