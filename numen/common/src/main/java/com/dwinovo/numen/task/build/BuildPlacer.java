package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.BlockWriter;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * 把施工图里的一格放进世界:一格怎么落进世界只有一条路,直接写。她站在哪儿、离这一格多远、中间隔着什么都不看。
 *
 * <p>我们一种方块都不认识,要问的只有两句,都由方块自己答(原版方块用原版的规矩,模组方块用模组的规矩):
 * <ul>
 *   <li>"你能待在这儿吗"——{@code BlockState.canSurvive},写之前问({@link #canStay});</li>
 *   <li>"照着周围,你该是什么样"——{@code Block.updateFromNeighbourShapes}(栅栏与玻璃板连上邻居、楼梯算好拐角、
 *       火把没有依附就是空气),写进去之后问。</li>
 * </ul>
 *
 * <p>{@link #place} 把图纸的状态原样交给她的手({@link NumenPlayer#writer}:写进世界、灌方块实体的数据、"我被放下了"、这一格自己收一次
 * 更新,身体是同伴时先过权限层),写成了才扣料;世界没变(如超出建造高度)或手没让写(主人不许、要问)就是没放下,不扣。放下之后
 * 世界说了算:原版随后弹掉或改了样子的,收工对图纸时如实报。
 *
 * <p><b>分工</b>——本类只管一格怎么落进世界;这一格该不该放、料够不够、放完怎么记账是 {@link BuildCompanionTask} 的事,
 * 演出是 {@link BuildShowmanship}。
 */
final class BuildPlacer {

    /**
     * 一格交给手之后的结果。
     *
     * @param write 手交回的话:写了、世界没变、或被拒(带理由与要问的那一条)
     * @param spent 为它花掉的每一叠(样子与件数,免耗材为空)——回执说"用了哪一叠"读的就是它;只有写了才有
     */
    record Placement(BlockWriter.Write write, List<ItemStack> spent) {}

    private final NumenPlayer player;
    private final BuildInventory inv;

    BuildPlacer(NumenPlayer player, BuildInventory inv) {
        this.player = player;
        this.inv = inv;
    }

    /** 问方块自己:图纸要的样子在这一格站不站得住。 */
    boolean canStay(BuildTaskRecord.Target target) {
        return target.desiredState().canSurvive(player.level(), target.pos());
    }

    /**
     * 把图纸的状态写进这一格。
     *
     * <p>写下去的是她背包里选中的那一叠,不是同名的白货:要放的那一种由 {@link BuildInventory#pick} 定,随后扣的、回执报的
     * 还是这一叠。免耗材({@code cost} 为 0)时没有背包里的叠,用一件新的。
     *
     * @param cost 这一格要花几件;免耗材时由调用方给 0
     * @return 手交回的话与花了哪几叠;料刚好没了为 null
     */
    Placement place(BuildTaskRecord.Target target, int cost) {
        var pos = target.pos();
        BlockState desired = target.desiredState();
        Optional<Stacks.Pick> pick = cost > 0 ? inv.pick(target.item()) : Optional.empty();
        if (cost > 0 && pick.isEmpty()) {
            return null;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这格放下
        }
        ItemStack placing = pick.map(Stacks.Pick::sample).orElseGet(() -> new ItemStack(target.item()));
        BlockWriter.Write write = player.writer().write(pos, desired, placing);
        if (!(write instanceof BlockWriter.Write.Written)) {
            return new Placement(write, List.of());
        }
        return new Placement(write, pick.map(p -> inv.consume(p, cost)).orElse(List.of()));
    }
}
