package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;

/**
 * 收工阶段的善后:给工地外围的水重排流体 tick。
 */
final class BuildFixtures {

    private final NumenPlayer player;
    private final BuildTaskRecord r;

    BuildFixtures(NumenPlayer player, BuildTaskRecord r) {
        this.player = player;
        this.r = r;
    }

    /**
     * 收工时给紧贴工地外围一圈的水重排一次流体 tick。
     *
     * <p>我们落位不带邻居更新——那是为了不让原版中途改写图纸,但代价是<b>周围的水
     * 不知道世界变了</b>:在湖里砌一道墙,两侧的水停在过期状态;把水下的一块石头
     * 清掉,那个洞不会自己被水填上。踢一脚只需要在外壳上做,内部全是刚放好的方块。
     *
     * <p>只在有格子是"连带清空"那档时做:低档位不清场,挖不出会漏水的空腔。
     */
    void nudgeSurroundingWater(BlockPos siteMin, BlockPos siteMax) {
        if (r.targets.stream().noneMatch(t -> t.mode() == ReplaceMode.REPLACE_EMPTY)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos.betweenClosedStream(siteMin.offset(-1, -1, -1), siteMax.offset(1, 1, 1))
                .filter(pos -> pos.getX() < siteMin.getX() || pos.getX() > siteMax.getX()
                        || pos.getY() < siteMin.getY() || pos.getY() > siteMax.getY()
                        || pos.getZ() < siteMin.getZ() || pos.getZ() > siteMax.getZ())
                .filter(pos -> level.isLoaded(pos) && level.getFluidState(pos).is(Fluids.WATER))
                .forEach(pos -> level.scheduleTick(pos.immutable(), Fluids.WATER,
                        Fluids.WATER.getTickDelay(level)));
    }

    /**
     * 手里的 NBT 包成一次性只读视图。1.21.6+ 的存档 IO 重构把实体与方块实体的读取入口
     * 从 {@code CompoundTag} 换成了 {@code ValueInput},这里做的只是形状适配:
     * 问题上报丢弃(旧代同样是读坏了就当没读到),内容与旧代直接喂 NBT 完全一致。
     */
    static net.minecraft.world.level.storage.ValueInput asInput(
            net.minecraft.nbt.CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        return net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, registries, tag);
    }
}
