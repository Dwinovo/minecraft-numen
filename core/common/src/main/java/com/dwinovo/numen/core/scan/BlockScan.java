package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 看某几种方块在哪:从她脚下按半径找({@link BlockSearch}),可以只看一块区域里的,命中的每一格用挖掘落点会提交的同一个动作
 * 问一次权限层,相连且说法相同的成一团({@link BlockGroups})。这是 {@code scan blocks} 的看法——看完的结果能原样写进一块区域
 * ({@link Found#into}),{@code work mine} 挖的就是那块区域里的格,挖矿自己不找候选。
 */
public final class BlockScan {

    /** 看多远的上限(格)。 */
    public static final int MAX_RADIUS = 192;

    private BlockScan() {}

    /**
     * 一次看的结果。
     *
     * @param groups   分好的团,由近及远
     * @param coverage 搜索的覆盖账:读到了哪、有没有截断
     * @param center   从哪一格看的(她当时脚下)
     * @param tick     看的那一刻(主世界游戏刻):写进区域时每格附带的"当时"
     */
    public record Found(List<BlockGroups.Group> groups, BlockSearch.ScanResult coverage, BlockPos center, long tick) {

        /**
         * 每一团加成 {@code area} 的一部分(格子附带看到的方块与这一刻),编号按这块区域 {@code g} 的计数续下去。
         *
         * @return 加好的区域,与各团拿到的编号(和 {@link #groups} 一一对应)
         */
        public Added into(Area area) {
            Area out = area;
            List<String> ids = new ArrayList<>(groups.size());
            for (BlockGroups.Group group : groups) {
                out = out.with(Area.Kind.GROUP, Cells.seen(group.cells(), tick));
                ids.add(out.parts().get(out.parts().size() - 1).id());
            }
            return new Added(out, ids);
        }
    }

    /** 写进区域之后:新区域,与每一团的编号。 */
    public record Added(Area area, List<String> ids) {}

    /**
     * 起一次看:以 {@code self} 此刻脚下那一格为中心、半径 {@code radius} 找 {@code targets};结果在之后某一刻经 {@code done}
     * 回来。{@code within} 非空时只收落在这块区域里的格(判定是区域自己的 {@link Area#contains})。
     *
     * @return 这次搜索的句柄,交 {@link BlockSearch#cancel} 撤掉
     */
    public static int start(NumenPlayer self, int radius, Set<Block> targets, Area within, Consumer<Found> done) {
        ServerLevel level = self.serverLevel();
        BlockPos center = self.blockPosition();
        Judged judged = new Judged(self, level, targets, within);
        // want 取收集上限:团要整团给,不能在"最近的几格已经证明"时就停,走满半径,内存由上限兜住
        return BlockSearch.start(self.getUUID(), level, center, radius, BlockSearch.MAX_COLLECT, targets, judged,
                result -> done.accept(new Found(judged.groups.grouped(center), result, center,
                        self.getServer().overworld().getGameTime())));
    }

    /**
     * 分团前的逐格处理:现读这一格(走完之后可能已经变了,不再是目标的不算),不在要看的区域里的不算;拿挖掘落点会提交的同一个
     * 动作 {@link Action#breakBlock} 在主线程对活世界问权限层({@link Gate#judgeLive}),连同说法收进分团。一次看取一份裁决快照,
     * 和任务动手前逐个裁决一批动作是同一种问法。
     */
    private static final class Judged implements Consumer<BlockScanner.Hit> {
        private final NumenPlayer self;
        private final ServerLevel level;
        private final Set<Block> targets;
        private final Area within;
        final BlockGroups groups = new BlockGroups();
        private Gate gate;

        Judged(NumenPlayer self, ServerLevel level, Set<Block> targets, Area within) {
            this.self = self;
            this.level = level;
            this.targets = targets;
            this.within = within;
        }

        @Override
        public void accept(BlockScanner.Hit hit) {
            if (within != null && !within.contains(level.dimension(), hit.pos())) {
                return;
            }
            BlockState live = level.getBlockState(hit.pos());
            if (!targets.contains(live.getBlock())) {
                return;
            }
            if (gate == null) {
                gate = Permission.gateFor(self);
            }
            groups.add(hit.pos(), live, gate.judgeLive(Action.breakBlock(hit.pos(), live), level));
        }
    }
}
