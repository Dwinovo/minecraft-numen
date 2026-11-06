package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** 段状态机为什么收场。都是事实,不是给人看的话;门面据此组出结局。 */
public sealed interface Halt {

    /**
     * 搜索没交出能走的路,或这一趟的总预算用完了({@code stop} 是 BUDGET):{@code stop} 是它为什么停,{@code breathless} 是它有没有因为
     * 憋不住气丢下过步子,{@code search} 是最后那次搜索的全部输入(快照、成本模型、起点、目标、预算),门面在同一份快照上诊断为什么
     * 没路,{@code blocking} 是那次搜索里离目标最近的节点上朝目标的步子为什么被拒(没有为 null)。
     */
    record Searched(SearchResult.Stop stop, boolean breathless, Search search, SearchResult.Blocking blocking) implements Halt {}

    /** 一步走不下去,从这里重搜把能走的都搜过了还是没路:没路的原因就是那一步。 */
    record Blocked(Blockage blockage) implements Halt {}

    /** 动手被拒:{@code reason} 是拒绝方({@link com.dwinovo.numen.api.entity.Mouse.Refusal})自己的理由,模块不解读。 */
    record Denied(BlockPos cell, Object reason) implements Halt {}

    /** 到了,但目标要求看得见的那一格看不见。 */
    record NoSight(BlockPos target) implements Halt {}

    /** 飞不下去了:收场时离目标还远,而此刻从这里飞不起来({@link Lack})。 */
    record Grounded(Lack lack) implements Halt {}

    /** 身体待不住:卡在方块里、悬在半空,一直没落定到任何一个节点上。 */
    record Stranded(BlockPos cell, BlockState block) implements Halt {}
}
