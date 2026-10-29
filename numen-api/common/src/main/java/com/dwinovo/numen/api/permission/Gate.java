package com.dwinovo.numen.api.permission;


import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Policy;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * 一次裁决用的快照:模式、两层规则、所在维度的放置记录、主人答应下来的任务期授权。
 * 主线程建({@link Permission#gateFor}),之后任何线程只读——寻路工作线程拿着它给每条边定价。
 *
 * <p>判定顺序与出厂层、主人层怎么叠都在规则核心({@link Policy});这里只做游戏适配:把一下动作和世界此刻的
 * 事实({@link Facts})接成规则核心问对象的口({@link ActView}),并把任务期授权({@link ConsentItem#covers})
 * 接到核心的授权口上。
 *
 * <p>两种问法,由调用方按所在线程选:{@link #judge} 只读给定的视图与放置记录,任何线程可调,
 * 要活读世界的信号按保守值答;{@link #judgeLive} 主线程专用,信号可以读方块实体内容。
 */
public final class Gate {

    private final Actor actor;
    private final Policy policy;
    private final PlacedBlocks placed;
    private final Structures structures;
    private final boolean unknownAsOther;
    private final List<ConsentItem> granted;
    private final Hits hits;

    /**
     * @param actor      要动手的同伴是谁、她的名字与主人;测试可传 {@link Actor#NOBODY}
     * @param policy     模式、硬规则({@link FactoryRules#hard},服主关掉了是空表)与两层规则:先主人自己写的
     *                   ({@link PermissionStore#rules}),后出厂的({@link FactoryRules#layer})
     * @param placed     她所在维度的放置记录:这份快照裁决的动作都发生在那里
     * @param structures 她身边世界生成时记下的结构件范围
     * @param unknownAsOther 说不清是谁的方块按别人的算({@link PermissionConfig#unknownAsOther})
     * @param granted    主人答应下来的任务期授权({@link ConsentDesk#granted})
     * @param hits       真动手的裁决({@link #judgeLive})定下答复的那一行规则记一次命中
     */
    public Gate(Actor actor, Policy policy, PlacedBlocks placed, Structures structures, boolean unknownAsOther,
                List<ConsentItem> granted, Hits hits) {
        this.actor = actor;
        this.policy = policy;
        this.placed = placed;
        this.structures = structures;
        this.unknownAsOther = unknownAsOther;
        this.granted = List.copyOf(granted);
        this.hits = hits;
    }

    public Mode mode() {
        return policy.mode();
    }

    /** 任何线程:只读 {@code view}(活世界或搜索快照)与放置记录裁决一个动作。 */
    public Verdict judge(Action action, BlockGetter view) {
        return decide(action, facts(view, null));
    }

    /**
     * 主线程:对活世界裁决一个动作,信号可以读方块实体内容。这是真要动手前的那一问,定下答复的那一行规则记一次命中;
     * 规划时逐格定价的 {@link #judge} 不记。
     */
    public Verdict judgeLive(Action action, ServerLevel level) {
        Verdict verdict = decide(action, facts(level, level));
        if (verdict.rule() != null) {
            hits.record(verdict.layer(), verdict.kind(), verdict.rule());
        }
        return verdict;
    }

    /** 任何线程:把 {@link #judge} 问出来的动作写成一条征询,与裁决同一份视图。 */
    public ConsentItem consentItem(Action action, Verdict verdict, BlockGetter view) {
        return ConsentItem.of(action, verdict, facts(view, null));
    }

    /** 主线程:把 {@link #judgeLive} 问出来的动作写成一条征询,与裁决同一份活世界。 */
    public ConsentItem consentItemLive(Action action, Verdict verdict, ServerLevel level) {
        return ConsentItem.of(action, verdict, facts(level, level));
    }

    private Facts facts(BlockGetter view, ServerLevel live) {
        return new Facts(view, placed, live, actor, structures, unknownAsOther);
    }

    private Verdict decide(Action action, Facts facts) {
        return policy.judge(new ActView(action, facts), hit -> granted.stream().anyMatch(g -> g.covers(action, hit)));
    }
}
