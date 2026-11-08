package com.dwinovo.numen.nav;

import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.init.InitTag;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 垫路料:一趟路往上垫柱、过沟搭桥时愿意消耗掉的方块(名字取 Baritone 的 acceptableThrowawayItems)。哪几种由走路的一方给
 * (按先后就是挑的先后);模型走路用的是出厂那一份({@link #factory}),反射(逃跑、脱困、摔落自救)走的也是它。料是这一趟的事,不记在她
 * 身上。
 *
 * <h2>出厂那一份的判据</h2>
 * 标签 {@code numen:throwaway}:遍地都是、没有功能、<b>放下去不会掉</b>——重力方块(沙、砂砾)一个不要,垫在半空会直接落下去。
 * 标签内容来自数据包,每次现查,{@code /reload} 改了下一趟就生效;整合包改这个标签就改掉所有同伴的出厂料。
 */
public final class ThrowawayBlocks {

    private ThrowawayBlocks() {}

    /** 出厂的那一份:标签 {@code numen:throwaway} 此刻的成员。 */
    public static List<Item> factory() {
        List<Item> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(InitTag.THROWAWAY)) {
            out.add(holder.value());
        }
        return List.copyOf(out);
    }

    /**
     * 端口 {@link com.dwinovo.numen.pathing.plan.Materials} 的答案:下一次垫路会放下哪种方块——按 {@code list} 的先后,主背包或
     * 副手里有哪一种就是哪一种;免耗材画像(创造)下就是清单第一种(寻路拿到手上时凭空取一叠)。只看不拿;没有料可垫为空。
     */
    public static Optional<Block> next(ServerPlayer player, List<Item> list) {
        Inventory inventory = player.getInventory();
        for (Item item : list) {
            if (item instanceof BlockItem block && (inventory.contains(new ItemStack(item))
                    || player.getOffhandItem().is(item))) {
                return Optional.of(block.getBlock());
            }
        }
        if (WorkProfile.of(player).freeMaterials()) {
            for (Item item : list) {
                if (item instanceof BlockItem block) {
                    return Optional.of(block.getBlock());
                }
            }
        }
        return Optional.empty();
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * 每条路都要垫方块而身上一件料都没有时的那句话:这一趟能垫哪几种,背包里带着哪些别的方块(走路不垫它们)。背包里的方块
     * 一样不落地列:种数不会多过背包的格数。
     */
    public static String shortageAdvice(ServerPlayer player, List<Item> list) {
        Inventory inv = player.getInventory();
        Map<String, Integer> spare = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && !list.contains(stack.getItem())) {
                spare.merge(idOf(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        String carrying = spare.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> e.getKey() + "×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String allowed = list.isEmpty() ? "none" : String.join(", ", list.stream().map(ThrowawayBlocks::idOf).toList());
        StringBuilder out = new StringBuilder(" I carry none of the blocks this walk may spend (").append(allowed)
                .append(").");
        if (carrying.isEmpty()) {
            return out.append(" Get some of those blocks first.").toString();
        }
        return out.append(" I do carry ").append(carrying)
                .append(", which a walk does not spend: get some of the blocks above.").toString();
    }
}
