package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.MoveKind;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;
import net.minecraft.core.BlockPos;

/**
 * 一次搜索的结论:为什么停,以及交出的路线。到了目标或这一段的终点交出整条;没到时交出往边界上一个节点去的一截,去哪、截到哪由
 * {@link HandOver} 定(边界上总价最小的节点、截到回得了头的地方、离起点足够远),交不出就没有路线。
 *
 * @param stop          为什么停
 * @param route         交出的路线;没有为 null
 * @param expanded      展开了几个节点
 * @param breathless    有前提成立的步子因为走过去憋不住气({@link com.dwinovo.numen.pathing.plan.Breath#lasts})没走:没路时诊断
 *                      先问"憋得住的话有没有路"
 * @param startEstimate 起点的估价(刻,与搜索用的是同一份,含这一趟记忆里抬高的增量):一趟的总预算与它成正比
 * @param raised        没到目标时,展开过的格子的估价该抬高多少({@link Learning}),格 → 比搜索用的估价再多多少;
 *                      到了或没有可学的为空
 * @param blocking      没到目标时,离目标最近的那个节点上朝目标的步子被拒的头一条(哪种走法、从哪个节点、哪一格、哪一条前提),
 *                      收场说"缺什么"取它;没有为 null
 * @param checks        取出未核的候选时精确判断的账:按走法记判了几次、几次不成立、几次真价比估价贵
 */
public record SearchResult(Stop stop, Route route, int expanded, boolean breathless, double startEstimate,
                           Long2DoubleMap raised, Blocking blocking, Checks checks) {

    /** 搜索为什么停。"没有路"只有 {@link #EXHAUSTED} 能说;时间到、节点上限、碰到没加载的区块只是没搜完。 */
    public enum Stop {
        /** 到了目标。 */
        ARRIVED,
        /** 到了这一段的终点:一个估价比起点低一段的节点({@link Search#advance}),目标还在前面。 */
        SEGMENT,
        /** 这次搜索的时限到了({@link Search#deadline}),还没到目标。 */
        TIME,
        /** 按这次的规格走得到的节点全搜过了:真的到不了。 */
        EXHAUSTED,
        /** 展开节点的上限用完了,还没搜完。 */
        BUDGET,
        /** 走得到的都搜过了,但有路伸进了快照之外没加载的区块,那边是什么不知道。 */
        UNLOADED,
        /** 起点上身体站不住、攀不住也浮不住,无从出发。 */
        STRANDED,
        /** 被叫停了。 */
        CANCELLED
    }

    /**
     * 离目标最近的节点上,朝目标的一步被拒了。
     *
     * @param move  哪种走法
     * @param from  从哪个节点
     * @param fails 被拒的是哪一格、哪一条前提
     */
    public record Blocking(MoveKind move, BlockPos from, Premise.Fails fails) {}

    /**
     * 取出未核的候选时精确判断的账(惰性评估,{@link AStar}),下标是 {@link MoveKind#ordinal}:快判断对哪种走法太乐观,看它取出来
     * 不成立、真价更贵的比例。
     *
     * @param checked 精确判了几次
     * @param refused 其中不成立的
     * @param dearer  其中成立、真价比快判断的估价贵、按真价放回去的
     */
    public record Checks(int[] checked, int[] refused, int[] dearer) {

        public static Checks none() {
            int n = MoveKind.values().length;
            return new Checks(new int[n], new int[n], new int[n]);
        }
    }

    public SearchResult {
        raised = raised == null ? Long2DoubleMaps.EMPTY_MAP : raised;
    }

    /** 到了目标。 */
    public boolean arrived() {
        return stop == Stop.ARRIVED;
    }

    /** 到了这次搜索要去的地方:目标,或这一段的终点。交出的是完整的一截,不经 {@link HandOver} 截。 */
    public boolean reached() {
        return stop == Stop.ARRIVED || stop == Stop.SEGMENT;
    }
}
