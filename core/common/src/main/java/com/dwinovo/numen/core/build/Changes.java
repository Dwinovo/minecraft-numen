package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 把一处变成施工图的样子要动哪些格:施工图在这里求出的<b>每一格应该是什么</b>,和世界里的<b>实际</b>方块比——已经对的不动,
 * 缺的补,不一样的换;再加上这一栋从前由她放下、施工图里已经没有的格,只拆"世界里现在仍是她放下的那个方块"的。
 * 第一次盖时这一栋还没有记录,差异就是整栋:盖与改是同一条路、同一个计算。
 *
 * <p>交给执行器的活是施工图的全部格加上要拆的格——执行器本来就逐格对着世界跳过已经对的格,施工中被外力弄坏的也照样补回来;
 * 这里的几个数只回答"有没有要动的、各是多少",没有就不派活。
 *
 * @param work    交给执行器的目标格:施工图的每一格,接着要拆的格({@link BuildTaskRecord.Target#removal})
 * @param fill    空着(或只有草这类软东西)、要补上的格
 * @param replace 立着别的东西、要换掉的格(施工图要空气的格上立着东西,也是换)
 * @param remove  这一栋从前由她放下、施工图里已经没有、世界里仍是那个方块的格
 * @param unseen  所在区块没加载、此刻看不见的格:到了那儿执行器自己对照,算作要去看一眼
 */
public record Changes(List<BuildTaskRecord.Target> work, int fill, int replace, int remove, int unseen) {

    /** 没有要动的:这里已经是施工图的样子。 */
    public boolean none() {
        return fill + replace + remove + unseen == 0;
    }

    /**
     * @param targets 施工图摆在这里的每一格(世界坐标)
     * @param was     这一处已经盖过的那一栋;第一次盖是 null
     * @param world   世界里这一格此刻是什么;区块没加载是 null——读不到就不猜
     */
    public static Changes between(List<BuildTaskRecord.Target> targets, Built.Building was,
                                  Function<BlockPos, BlockState> world) {
        int fill = 0;
        int replace = 0;
        int remove = 0;
        int unseen = 0;
        Set<Long> drawn = new HashSet<>();
        List<BuildTaskRecord.Target> work = new ArrayList<>(targets);
        for (BuildTaskRecord.Target t : targets) {
            drawn.add(t.pos().asLong());
            BlockState now = world.apply(t.pos());
            if (now == null) {
                unseen++;
            } else if (!t.matches(now) && t.mode().allows(now, t.desiredState())) {
                boolean empty = now.isAir() || now.canBeReplaced();
                if (empty && !t.desiredState().isAir()) {
                    fill++;
                } else {
                    replace++;
                }
            }
        }
        if (was != null) {
            for (Map.Entry<Long, Block> cell : was.cells().entrySet()) {
                if (drawn.contains(cell.getKey())) {
                    continue;
                }
                BlockPos pos = BlockPos.of(cell.getKey());
                BlockState now = world.apply(pos);
                if (now == null) {
                    unseen++;
                    work.add(BuildTaskRecord.Target.removal(pos, cell.getValue()));
                } else if (now.is(cell.getValue())) {
                    remove++;
                    work.add(BuildTaskRecord.Target.removal(pos, cell.getValue()));
                }
            }
        }
        return new Changes(List.copyOf(work), fill, replace, remove, unseen);
    }
}
