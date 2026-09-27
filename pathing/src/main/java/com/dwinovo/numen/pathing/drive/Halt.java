package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** 段状态机为什么收场。都是事实,不是给人看的话;门面据此组出结局。 */
public sealed interface Halt {

    /**
     * 搜索没交出能走的路:{@code stop} 是它为什么停,{@code search} 是那次搜索的全部输入(快照、成本模型、起点、目标、预算),
     * 门面在同一份快照上诊断为什么没路。
     */
    record Searched(SearchResult.Stop stop, Search search) implements Halt {}

    /** 一步走不下去,重搜也绕不过去,或同一步几次都走不下去。 */
    record Blocked(Blockage blockage) implements Halt {}

    /** 动手被拒:{@code reason} 是拒绝方({@link com.dwinovo.numen.pathing.body.Effector})自己的理由。 */
    record Denied(BlockPos cell, Object reason) implements Halt {}

    /** 到了,但目标要求看得见的那一格看不见。 */
    record NoSight(BlockPos target) implements Halt {}

    /** 身体待不住:卡在方块里、悬在半空,一直没落定到任何一个节点上。 */
    record Stranded(BlockPos cell, BlockState block) implements Halt {}
}
