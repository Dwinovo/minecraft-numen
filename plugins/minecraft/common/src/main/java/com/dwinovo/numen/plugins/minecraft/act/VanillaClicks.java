package com.dwinovo.numen.plugins.minecraft.act;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.Replaceable;
import com.dwinovo.numen.api.permission.Action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * 原版里"这一下右键会对世界做什么"的回答者,一个只认一种物品:方块物品落进被点的那一格(它本身可以被顶替时)或那一面前面的一格,
 * 打火石与火焰弹在那一面前面点起火,桶不看点的是哪一格——它在服务端自己沿她的视线打一条线,在那条线碰上的地方倒出或舀起液体。
 * 都说成一次放置。经插件的门登记({@link #install}),和模组的回答者同一个写法;多认一种就是在这里多写一个、列进 {@link #install}。
 * 只说会做什么,许不许由权限层裁决。
 */
public final class VanillaClicks {

    private VanillaClicks() {}

    public static void install(NumenApi numen) {
        numen.answer(Questions.CLICK, "block_item", (body, hit, stack) ->
                stack.getItem() instanceof BlockItem block ? placing(body, Replaceable.landing(body.serverLevel(),
                        hit.getBlockPos(), hit.getDirection(), block.getBlock()), stack) : List.of());
        numen.answer(Questions.CLICK, "bucket", (body, hit, stack) ->
                stack.getItem() instanceof BucketItem ? placing(body, bucketTarget(body, stack), stack) : List.of());
        numen.answer(Questions.CLICK, "flint_and_steel", (body, hit, stack) ->
                stack.getItem() instanceof FlintAndSteelItem ? placing(body, front(hit), stack) : List.of());
        numen.answer(Questions.CLICK, "fire_charge", (body, hit, stack) ->
                stack.getItem() instanceof FireChargeItem ? placing(body, front(hit), stack) : List.of());
    }

    /** 被点的那一面前面那一格。 */
    private static BlockPos front(BlockHitResult hit) {
        return hit.getBlockPos().relative(hit.getDirection());
    }

    /**
     * 桶这一下动哪一格,照原版 {@code BucketItem.use}:物品自己沿视线打的那一条({@link Mouse#itemRay},空桶认水源、满桶不看液体)
     * 碰上的那一格——空桶舀的是它,满桶倒在碰上的那一面前面。那条线什么也没碰上为 null。
     */
    private static BlockPos bucketTarget(ServerPlayer body, ItemStack stack) {
        boolean empty = stack.is(Items.BUCKET);
        BlockHitResult ray = Mouse.itemRay(body, empty ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
        if (ray.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return empty ? ray.getBlockPos() : front(ray);
    }

    /** 把这一叠往 {@code at} 那一格放的那一个动作;{@code at} 为 null(放不下、没碰上)就没有。 */
    private static List<Action> placing(ServerPlayer body, BlockPos at, ItemStack stack) {
        return at == null ? List.of() : List.of(Action.place(at, body.serverLevel().getBlockState(at), stack.getItem()));
    }
}
