package com.dwinovo.numen.core.pathing.moves.movements;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.BuildValidity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Active construction material lookup used by path movements that place blocks. */
public final class BuildPlacementRegistry {

    public interface Provider {
        BlockState desiredState(BlockPos placeAt);

        default boolean acceptsPlacement(BlockPos placeAt, BlockState state) {
            BlockState desired = desiredState(placeAt);
            return desired != null && BuildValidity.valid(state, desired, true);
        }
    }

    private static final Map<ServerPlayer, Provider> PROVIDERS = new WeakHashMap<>();
    /** 施工期寻路放置过的格子回执(仅登记了 Provider 的玩家记录;任务逐 tick 领走)。 */
    private static final Map<ServerPlayer, java.util.Set<BlockPos>> SCAFFOLD = new WeakHashMap<>();

    private BuildPlacementRegistry() {}

    /** 寻路准备在该格放置(脚手架/垫柱/搭桥):给建造任务留回执,交付前清残料用。 */
    public static void recordScaffold(ServerPlayer player, BlockPos placeAt) {
        if (!PROVIDERS.containsKey(player)) {
            return;   // 无建造任务在册:挖矿等场景的搭桥不记账,防集合无界生长
        }
        SCAFFOLD.computeIfAbsent(player, k -> new java.util.LinkedHashSet<>()).add(placeAt.immutable());
    }

    /** 领走该玩家累计的放置回执(领后清空)。 */
    public static java.util.Set<BlockPos> drainScaffold(ServerPlayer player) {
        java.util.Set<BlockPos> out = SCAFFOLD.remove(player);
        return out == null ? java.util.Set.of() : out;
    }

    public static void register(ServerPlayer player, Provider provider) {
        PROVIDERS.put(player, provider);
    }

    public static void unregister(ServerPlayer player, Provider provider) {
        if (PROVIDERS.get(player) == provider) {
            PROVIDERS.remove(player);
        }
    }


    static boolean hasTarget(ServerPlayer player, BlockPos placeAt) {
        Provider provider = PROVIDERS.get(player);
        return provider != null && provider.desiredState(placeAt) != null;
    }

    static boolean selectForLocation(ServerPlayer player, BlockPos placeAt, boolean select) {
        return selectForLocation(player, placeAt, null, player.getYRot(), player.getXRot(), select);
    }

    static boolean selectForLocation(ServerPlayer player, BlockPos placeAt, BlockHitResult hit,
                                     float yaw, float pitch, boolean select) {
        Provider provider = PROVIDERS.get(player);
        if (provider == null) {
            return false;
        }
        BlockState desired = provider.desiredState(placeAt);
        if (desired == null) {
            return false;
        }
        if (selectMatching(player, select, stack -> wouldPlaceAccepted(player, stack, placeAt,
                hit, yaw, pitch, provider))) {
            return true;
        }
        if (selectMatching(player, select, stack -> wouldPlaceSameBlock(player, stack, desired,
                hit, yaw, pitch))) {
            return true;
        }
        return selectGenericThrowaway(player, hit, yaw, pitch, select);
    }

    private static boolean selectMatching(ServerPlayer player, boolean select, Predicate<ItemStack> desired) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && desired.test(stack)) {
                if (select) {
                    inv.setSelectedSlot(i);
                }
                return true;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && desired.test(offhand)) {
            // 建材在副手:选一个空/工具主手槽,让右键走副手放置(顺序 快捷栏→副手→背包)。
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()
                        || stack.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL)) {
                    if (select) {
                        inv.setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }
        if (NavSettings.get().allowInventory) {
            for (int i = 9; i < inv.getNonEquipmentItems().size(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && desired.test(stack)) {
                    if (select) {
                        if (player instanceof NumenPlayer np) {
                            np.holdInHand(i);
                        } else {
                            ItemStack tmp = inv.getItem(7);
                            inv.setItem(7, stack);
                            inv.setItem(i, tmp);
                            inv.setSelectedSlot(7);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean wouldPlaceAccepted(ServerPlayer player, ItemStack stack, BlockPos placeAt,
                                              BlockHitResult hit, float yaw, float pitch,
                                              Provider provider) {
        if (hit == null) {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            BlockState fallback = blockItem.getBlock().defaultBlockState();
            return provider.acceptsPlacement(placeAt, fallback);
        }
        BlockState state = predictedState(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND);
        return state != null && provider.acceptsPlacement(placeAt, state);
    }

    private static boolean wouldPlaceSameBlock(ServerPlayer player, ItemStack stack, BlockState desired,
                                               BlockHitResult hit, float yaw, float pitch) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        if (hit == null) {
            return desired.getBlock() == blockItem.getBlock();
        }
        BlockState state = predictedState(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND);
        return state != null && state.getBlock() == desired.getBlock();
    }

    private static boolean selectGenericThrowaway(ServerPlayer player, BlockHitResult hit,
                                                  float yaw, float pitch, boolean select) {
        Inventory inventory = player.getInventory();
        boolean allowInventory = NavSettings.get().allowInventory;
        for (Item item : NavSettings.get().acceptableThrowawayItems()) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == item
                        && wouldPlaceAny(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND)) {
                    if (select) {
                        inventory.setSelectedSlot(i);
                    }
                    return true;
                }
            }
            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty() && offhand.getItem() == item
                    && wouldPlaceAny(player, offhand, hit, yaw, pitch, InteractionHand.OFF_HAND)) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack.isEmpty()
                            || stack.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL)) {
                        if (select) {
                            inventory.setSelectedSlot(i);
                        }
                        return true;
                    }
                }
            }
            if (allowInventory) {
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (!stack.isEmpty() && stack.getItem() == item
                            && wouldPlaceAny(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND)) {
                        if (select) {
                            ItemStack tmp = inventory.getItem(7);
                            inventory.setItem(7, stack);
                            inventory.setItem(i, tmp);
                            inventory.setSelectedSlot(7);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean wouldPlaceAny(ServerPlayer player, ItemStack stack, BlockHitResult hit,
                                         float yaw, float pitch, InteractionHand hand) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        return hit == null || predictedState(player, stack, hit, yaw, pitch, hand) != null;
    }

    private static BlockState predictedState(ServerPlayer player, ItemStack stack, BlockHitResult hit,
                                             float yaw, float pitch, InteractionHand hand) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, hand, stack, hit) {});
            BlockState state = blockItem.getBlock().getStateForPlacement(context);
            return state != null && context.canPlace() ? state : null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }
}