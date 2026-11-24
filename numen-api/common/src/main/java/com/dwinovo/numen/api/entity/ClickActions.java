package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.permission.Action;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * "这一下右键会对世界做什么"的一个回答者:只说它认得的那种物品。手里是 {@code stack}、右键按在方块的 {@code hit} 这一面上,按下去会做的
 * 事交回成动作(今天原版的几样都是一次放置:{@link Action#place});不认得这件物品、这一下不会动世界,交回空表。
 *
 * <pre>{@code
 * numen.answer(Questions.CLICK, "my_seed_bag", (body, hit, stack) -> {
 *     if (!(stack.getItem() instanceof MySeedBag)) {
 *         return List.of();                               // 不是我的东西
 *     }
 *     BlockPos at = hit.getBlockPos().relative(hit.getDirection());
 *     return List.of(Action.place(at, body.serverLevel().getBlockState(at), stack.getItem()));
 * });
 * }</pre>
 *
 * <p>规矩:只说会做什么,不判能不能做(那是权限层的事,说出来的每个动作它都裁决一次);不改世界、不动身体、不改这一叠。别的回答者说了
 * 什么你看不到,回答之间不讲先后,各人说的合在一起。抛了异常只丢你这一条、记进日志,那一下照别人说的问。
 *
 * <p>线程与世界:在服务端主线程上被叫,世界是这具身体所在的活世界({@code body.serverLevel()}),可以读方块实体。问的时机有两个,
 * 问的是同一圈:鼠标右键一格方块按下去之前({@link Mouse#use(net.minecraft.core.BlockPos, net.minecraft.core.Direction, boolean)}),和驱动者在受理一次点击前预问时({@link Mouse#intents});两只手各问一次,
 * 空手也问({@code stack} 是空的)。寻路规划不问这一圈:搜索线程给垫路定价时直接提出放置动作。右键实体、只用手里的东西不问。
 */
@FunctionalInterface
public interface ClickActions {

    /**
     * @param body  按键的那具身体
     * @param hit   被点的那一格方块与被点中的那一面
     * @param stack 那只手里的一叠;空手是空的一叠
     * @return 这一下会做的事;不认得、不会动世界是空表或 null
     */
    List<Action> actions(ServerPlayer body, BlockHitResult hit, ItemStack stack);

    /**
     * 问一圈:登记着的回答者挨个问一遍,各人说的动作按登记先后合在一起。一个回答者抛了异常只丢它那一条、记进日志(点它的名字),
     * 别的回答照常。只在服务端主线程上用。
     */
    static List<Action> of(ServerPlayer body, BlockHitResult hit, ItemStack stack) {
        List<Action> out = new ArrayList<>();
        Questions.CLICK.answerers().forEach((name, answerer) -> {
            try {
                List<Action> said = answerer.actions(body, hit, stack);
                if (said != null) {
                    // 表里夹着 null 是回答者的毛病,和它抛异常一样只丢它这一条
                    out.addAll(List.copyOf(said));
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; what it would do is not asked this time",
                        name, Questions.CLICK.id(), e);
            }
        });
        return out;
    }
}
