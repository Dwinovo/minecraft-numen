package com.dwinovo.numen.build;

import com.dwinovo.numen.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Function;

/**
 * 把一处变成这串格的样子要动哪些格:每一格应该是什么,和世界里的<b>实际</b>方块比——已经对的不动,缺的补,不一样的换。
 *
 * <p>交给执行器的活是全部格——执行器本来就逐格对着世界跳过已经对的格,施工中被外力弄坏的也照样补回来;
 * 这里的几个数只回答"有没有要动的、各是多少",没有就不派活。
 *
 * @param fill    空着(或只有草这类软东西)、要补上的格
 * @param replace 立着别的东西、要换掉的格(要空气的格上立着东西,也是换)
 * @param unseen  所在区块没加载、此刻看不见的格:到了那儿执行器自己对照,算作要去看一眼
 */
public record Changes(int fill, int replace, int unseen) {

    /** 没有要动的:这里已经是这串格的样子。 */
    public boolean none() {
        return fill + replace + unseen == 0;
    }

    /**
     * @param targets 这串格(世界坐标)
     * @param world   世界里这一格此刻是什么;区块没加载是 null——读不到就不猜
     */
    public static Changes between(List<BuildTaskRecord.Target> targets, Function<BlockPos, BlockState> world) {
        int fill = 0;
        int replace = 0;
        int unseen = 0;
        for (BuildTaskRecord.Target t : targets) {
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
        return new Changes(fill, replace, unseen);
    }
}
