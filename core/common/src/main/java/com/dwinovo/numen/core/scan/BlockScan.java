package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 看某几种方块在哪:从她脚下按半径找({@link BlockSearch}),相连的命中格成一团({@link BlockGroups})。这是
 * {@code numen.scan.blocks} 的看法:只读世界,不问权限、不存东西;挖不挖得成由挖的那一下过权限层。
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
     */
    public record Found(List<BlockGroups.Group> groups, BlockSearch.ScanResult coverage, BlockPos center) {}

    /**
     * 起一次看:以 {@code self} 此刻脚下那一格为中心、半径 {@code radius} 找 {@code targets};结果在之后某一刻经 {@code done}
     * 回来。
     *
     * @return 这次搜索的句柄,交 {@link BlockSearch#cancel} 撤掉
     */
    public static int start(NumenPlayer self, int radius, Set<Block> targets, Consumer<Found> done) {
        ServerLevel level = self.serverLevel();
        BlockPos center = self.blockPosition();
        Live live = new Live(level, targets);
        // want 取收集上限:团要整团给,不能在"最近的几格已经证明"时就停,走满半径,内存由上限兜住
        return BlockSearch.start(self.getUUID(), level, center, radius, BlockSearch.MAX_COLLECT, targets, live,
                result -> done.accept(new Found(live.groups.grouped(center), result, center)));
    }

    /** 分团前的逐格处理:现读这一格(走完之后可能已经变了,不再是目标的不算),收进分团。 */
    private static final class Live implements Consumer<BlockScanner.Hit> {
        private final ServerLevel level;
        private final Set<Block> targets;
        final BlockGroups groups = new BlockGroups();

        Live(ServerLevel level, Set<Block> targets) {
            this.level = level;
            this.targets = targets;
        }

        @Override
        public void accept(BlockScanner.Hit hit) {
            BlockState live = level.getBlockState(hit.pos());
            if (targets.contains(live.getBlock())) {
                groups.add(hit.pos(), live);
            }
        }
    }
}
