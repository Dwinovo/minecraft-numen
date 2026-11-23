package com.dwinovo.numen.act;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.permission.Action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * 原版里"这一下右键会对世界做什么"的回答者,一个只认一种物品:方块物品贴着命中面放进可替换的格,桶倒出或舀起液体,打火石与火焰弹
 * 点起火——都是往命中面前面那一格的一次放置。经插件的门登记({@link #install}),和模组的回答者同一个写法;多认一种就是在这里多写
 * 一个、列进 {@link #install}。只说会做什么,许不许由权限层裁决。
 */
public final class VanillaClicks {

    private VanillaClicks() {}

    public static void install(NumenApi numen) {
        numen.answer(Questions.CLICK, "block_item", (body, hit, stack) ->
                stack.getItem() instanceof BlockItem && body.serverLevel().getBlockState(front(hit)).canBeReplaced()
                        ? placing(body, hit, stack) : List.of());
        numen.answer(Questions.CLICK, "bucket", (body, hit, stack) ->
                stack.getItem() instanceof BucketItem ? placing(body, hit, stack) : List.of());
        numen.answer(Questions.CLICK, "flint_and_steel", (body, hit, stack) ->
                stack.getItem() instanceof FlintAndSteelItem ? placing(body, hit, stack) : List.of());
        numen.answer(Questions.CLICK, "fire_charge", (body, hit, stack) ->
                stack.getItem() instanceof FireChargeItem ? placing(body, hit, stack) : List.of());
    }

    /** 命中面前面那一格:放置落在这里。 */
    private static BlockPos front(BlockHitResult hit) {
        return hit.getBlockPos().relative(hit.getDirection());
    }

    /** 把这一叠往命中面前面那一格放的那一个动作。 */
    private static List<Action> placing(ServerPlayer body, BlockHitResult hit, ItemStack stack) {
        BlockPos at = front(hit);
        return List.of(Action.place(at, body.serverLevel().getBlockState(at), stack.getItem()));
    }
}
