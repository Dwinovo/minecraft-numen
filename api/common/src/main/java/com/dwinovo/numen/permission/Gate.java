package com.dwinovo.numen.permission;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

/**
 * 一次裁决用的快照:模式、规则、这一维度的放置记录、领地口。主线程建
 * ({@link Permission#gateFor}),之后任何线程只读——寻路工作线程拿着它给每条边定价。
 *
 * <p>判断顺序固定,第一个命中即定:模式 → 领地信号 → deny 表 → 熔断 → allow 表 → ask 表 →
 * 都不中即放行。规则是数据,信号是函数,这里只读它们。
 *
 * <p>两种问法,由调用方按所在线程选:{@link #judge} 只读给定的视图与放置记录,任何线程可调,
 * 要活读世界的信号按保守值答;{@link #judgeLive} 主线程专用,信号可以读方块实体内容与领地。
 */
public final class Gate {

    private final NumenPlayer actor;
    private final Mode mode;
    private final RuleSet rules;
    private final PlacedBlocks placed;
    private final TerritoryClaims claims;

    /**
     * @param actor  要动手的同伴;测试可传 null
     * @param placed 这一维度的放置记录
     * @param claims 领地 mod 的裁决口;没有就 {@link TerritoryClaims#NONE}
     */
    public Gate(NumenPlayer actor, Mode mode, RuleSet rules, PlacedBlocks placed, TerritoryClaims claims) {
        this.actor = actor;
        this.mode = mode;
        this.rules = rules;
        this.placed = placed;
        this.claims = claims;
    }

    public Mode mode() {
        return mode;
    }

    /** 任何线程:只读 {@code view}(活世界或搜索快照)与放置记录裁决一个动作。 */
    public Verdict judge(Action action, BlockGetter view) {
        return decide(action, new Facts(view, placed, null, claims, actor));
    }

    /** 主线程:对活世界裁决一个动作,信号可以读方块实体内容与领地。 */
    public Verdict judgeLive(Action action, ServerLevel level) {
        return decide(action, new Facts(level, placed, level, claims, actor));
    }

    private Verdict decide(Action action, Facts facts) {
        if (mode == Mode.BYPASS) {
            return Verdict.allow();
        }
        if (mode == Mode.OBSERVE) {
            return Verdict.deny("observe mode: " + action.kind().verb() + " would change the world");
        }
        if (Signals.CLAIMED.test(action, facts)) {
            return Verdict.deny("a land claim forbids it");
        }
        Rule hit = RuleSet.firstMatch(rules.deny(), action, facts);
        if (hit != null) {
            return Verdict.deny("denied by rule " + hit + " (" + hit.describe() + ")");
        }
        hit = RuleSet.firstMatch(RuleSet.CIRCUIT_BREAKERS, action, facts);
        if (hit != null) {
            return Verdict.ask(hit.describe());
        }
        if (RuleSet.firstMatch(rules.allow(), action, facts) != null) {
            return Verdict.allow();
        }
        hit = RuleSet.firstMatch(rules.ask(), action, facts);
        if (hit != null) {
            return Verdict.ask(hit.describe());
        }
        return Verdict.allow();
    }
}
