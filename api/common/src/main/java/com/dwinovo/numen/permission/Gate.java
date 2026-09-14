package com.dwinovo.numen.permission;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * 一次裁决用的快照:模式、规则、这一维度的放置记录、领地口、主人答应下来的任务期授权。主线程建
 * ({@link Permission#gateFor}),之后任何线程只读——寻路工作线程拿着它给每条边定价。
 *
 * <p>判断顺序固定,第一个命中即定:模式 → 外部强制(领地、出生点保护)→ deny 表 → allow 表 →
 * ask 表 → 都不中也问。代码里不写死"能"也不写死"不能":放行只来自 allow 行与主人选的 bypass,
 * 拒绝只来自 deny 行、主人选的 observe 与外部强制;其余一律问。问出来的动作若已被任务期授权覆盖
 * ({@link ConsentItem#covers}),就是放行。
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
    private final List<ConsentItem> granted;

    /**
     * @param actor   要动手的同伴;测试可传 null
     * @param placed  这一维度的放置记录
     * @param claims  领地 mod 的裁决口;没有就 {@link TerritoryClaims#NONE}
     * @param granted 主人答应下来的任务期授权({@link ConsentDesk#granted})
     */
    public Gate(NumenPlayer actor, Mode mode, RuleSet rules, PlacedBlocks placed, TerritoryClaims claims,
                List<ConsentItem> granted) {
        this.actor = actor;
        this.mode = mode;
        this.rules = rules;
        this.placed = placed;
        this.claims = claims;
        this.granted = List.copyOf(granted);
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
        for (Signals external : EXTERNAL) {
            if (external.test(action, facts)) {
                return Verdict.deny(external.description());
            }
        }
        Rule hit = RuleSet.firstMatch(rules.deny(), action, facts);
        if (hit != null) {
            return Verdict.deny("denied by rule " + hit + " (" + hit.describe() + ")");
        }
        if (RuleSet.firstMatch(rules.allow(), action, facts) != null) {
            return Verdict.allow();
        }
        hit = RuleSet.firstMatch(rules.ask(), action, facts);
        for (ConsentItem grant : granted) {
            if (grant.covers(action, hit)) {
                return Verdict.allow();
            }
        }
        return hit != null ? Verdict.ask(hit) : Verdict.uncovered();
    }

    /** 外部强制:服务器或领地 mod 说不,不进规则表、不问主人,回执写明是谁拦的。 */
    private static final List<Signals> EXTERNAL = List.of(Signals.CLAIMED, Signals.SPAWN_PROTECTED);
}
