package com.dwinovo.numen.core.task.reflex;

import com.dwinovo.numen.core.task.chain.ArmorChain;
import com.dwinovo.numen.core.task.chain.FoodChain;
import com.dwinovo.numen.core.task.chain.MLGChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.core.task.chain.UnstuckChain;

/**
 * numen-core's reflex roster: the five survival chains (which implement
 * {@link Reflex} themselves — chain shape untouched) plus the two pure policies,
 * registered once at {@code NumenCore.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 */
public final class CoreReflexes {

    /** {@code ToolSelect}'s durability guard — the "don't grind a nearly-broken
     *  tool to dust" policy consulted by every automatic hand swap. */
    public static final String TOOL_GUARD_ID = "tool_guard";
    /** {@code FoodPolicy} — what the auto-eat chain may pick on its own. */
    public static final String FOOD_POLICY_ID = "food_policy";

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new FoodChain());
        ReflexRegistry.register(new UnstuckChain());
        ReflexRegistry.register(new ArmorChain());
        ReflexRegistry.register(new PolicyReflex(TOOL_GUARD_ID,
                "干活时会自动换上最合适的工具,快碎的工具会收手不用"));
        ReflexRegistry.register(new PolicyReflex(FOOD_POLICY_ID,
                "自己找吃的时会避开有毒或有害的食物"));
    }
}
