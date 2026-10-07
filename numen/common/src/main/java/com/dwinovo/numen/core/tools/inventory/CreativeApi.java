package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * {@code numen.creative}:创造模式才有的事——凭空取东西,原版创造物品栏的假体。
 *
 * <p>原版创造玩家有创造物品栏,假玩家没有界面,{@code give} 就是那个界面:补齐的是原版创造本来就有的能力(同伴能进创造已过主人的权限门)。
 * 生存画像如实拒绝,把她往采集、合成、交易的正道上引。当场完成,不占任务槽。
 */
public final class CreativeApi {

    /** 一次最多一背包量级(36 格 × 64)。 */
    private static final int GIVE_MAX = 2304;

    private CreativeApi() {}

    public static void install(NumenApi numen) {
        numen.api("creative", "Creative mode only: conjuring items, like the creative menu.", CreativeApi.class);
    }

    /** 取哪一样、取几件。 */
    public record Give(@Doc("The item to conjure.") Item item,
                       @Doc("How many to take (1-" + GIVE_MAX + ").") @Omitted("take one, like /give")
                       Optional<Integer> count) {}

    /** 取到几件、现在背着几件。 */
    public record Took(int took, int carrying) {}

    @Fn("Conjure items into your inventory, like the creative menu.")
    @Example("numen.creative.give(\"minecraft:diamond\", {count = 64})")
    @Note("Fails in survival mode; there you mine, craft, loot or trade for items instead.")
    @Note("What doesn't fit in your inventory drops at your feet.")
    public static Took give(ServerCall call, Give args) {
        NumenPlayer companion = call.her();
        Item item = args.item();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (!WorkProfile.of(companion).freeMaterials()) {
            throw new ApiError(ErrorKind.DENIED, "survival mode can't conjure items — mine, craft, loot or trade for "
                    + id + " instead (numen.creative.give works only in creative mode)", null);
        }
        int want = Math.clamp(args.count().orElse(1), 1, GIVE_MAX);
        // 按满栈分批塞;背包塞不下的原版 add 会留在栈里,掉在脚下
        int remaining = want;
        while (remaining > 0) {
            int n = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, n);
            if (!companion.getInventory().add(stack) && !stack.isEmpty()) {
                companion.drop(stack, false);
            }
            remaining -= n;
        }
        return new Took(want, companion.getInventory().countItem(item));
    }
}
