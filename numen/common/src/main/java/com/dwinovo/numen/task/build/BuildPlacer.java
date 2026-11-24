package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.build.BuildStates;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * 把施工图里的一格放进世界:一格怎么落进世界只有一条路,直接写。她站在哪儿、离这一格多远、中间隔着什么都不看。
 *
 * <p>我们一种方块都不认识,要问的只有两句,都由方块自己答(原版方块用原版的规矩,模组方块用模组的规矩):
 * <ul>
 *   <li>"你能待在这儿吗"——{@code BlockState.canSurvive};</li>
 *   <li>"照着周围,你该是什么样"——{@code Block.updateFromNeighbourShapes}(栅栏与玻璃板连上邻居、楼梯算好拐角、
 *       火把没有依附就是空气)。</li>
 * </ul>
 * {@link #settle} 问这两句,答得出样子就是这一格能写下去的样子,站不住是 null;{@link #place} 把那个样子写进世界。
 *
 * <p>写下去之后:{@code setBlock} 带着 {@code UPDATE_ALL}(邻居当场按各自的规矩反应),背包里那一叠的数据灌进方块实体
 * (潜影盒里的东西、旗帜的花纹、头颅的主人、蜂箱里的蜂都跟着方块走),再给方块一次"我被放下了"({@code setPlacedBy}:
 * 门与床写另一半、命名牌与告示牌初始化)。写成了才扣料;{@code setBlock} 返回假(世界没变,如超出建造高度)就是没放下,
 * 不扣。放下之后世界说了算:原版随后弹掉或改了样子的,收工对图纸时如实报。
 *
 * <p><b>分工</b>——本类只管一格怎么落进世界;这一格该不该放、料够不够、放完怎么记账是 {@link BuildCompanionTask} 的事,
 * 演出是 {@link BuildShowmanship}。
 */
final class BuildPlacer {

    /** 一格落下了:为它花掉的每一叠(样子与件数,免耗材为空)——回执说"用了哪一叠"读的就是它。 */
    record Placement(List<ItemStack> spent) {}

    private final NumenPlayer player;
    private final BuildInventory inv;

    BuildPlacer(NumenPlayer player, BuildInventory inv) {
        this.player = player;
        this.inv = inv;
    }

    /**
     * 这一格此刻能写成什么样子:先问它站不站得住,站得住再让它照着周围算好自己的样子;站不住,或算出来是空气,是 null。
     *
     * <p>双格方块的主半(门的下半、床脚、高草的下半)不问形状:它们的另一半要等主半写下去、{@code setPlacedBy} 才立起来,此刻问,
     * 方块自己会答"另一半不在,我就是空气"。
     */
    BlockState settle(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState desired = target.desiredState();
        if (!desired.canSurvive(player.level(), pos)) {
            return null;
        }
        if (BuildStates.otherHalfOf(pos, desired) != null) {
            return desired;
        }
        BlockState shaped = Block.updateFromNeighbourShapes(desired, player.level(), pos);
        return shaped.isAir() ? null : shaped;
    }

    /**
     * 把 {@code settle} 问出来的样子写进这一格。
     *
     * <p>写下去的是她背包里选中的那一叠,不是同名的白货:要放的那一种由 {@link BuildInventory#pick} 定,随后扣的、回执报的
     * 还是这一叠。免耗材({@code cost} 为 0)时没有背包里的叠,用一件新的。
     *
     * @param cost 这一格要花几件;免耗材时由调用方给 0
     * @return 花了哪几叠;没放下(世界没变、料刚好没了)为 null
     */
    Placement place(BuildTaskRecord.Target target, BlockState settled, int cost) {
        BlockPos pos = target.pos();
        Optional<Stacks.Pick> pick = cost > 0 ? inv.pick(target.item()) : Optional.empty();
        if (cost > 0 && pick.isEmpty()) {
            return null;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这格放下
        }
        ItemStack placing = pick.map(Stacks.Pick::sample).orElseGet(() -> new ItemStack(target.item()));
        if (!player.level().setBlock(pos, settled, Block.UPDATE_ALL)) {
            return null;
        }
        BlockItem.updateCustomBlockEntityTag(player.level(), player, pos, placing);
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (entity != null) {
            entity.applyComponentsFromItemStack(placing);
            entity.setChanged();
        }
        settled.getBlock().setPlacedBy(player.level(), pos, settled, player, placing);
        return new Placement(pick.map(p -> inv.consume(p, cost)).orElse(List.of()));
    }
}
