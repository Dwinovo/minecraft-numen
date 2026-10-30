package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * "背包里的哪一叠":模型说的是物品 id,身上的是一叠叠带组件的真东西(装着钻石的潜影盒、附魔的镐、不同的药水)。
 * <b>把 id 落到具体一叠只在这里</b>——手放、直接写、拿到手上、丢出去、吃、穿戴都问它,放哪一叠、扣哪一叠、回执报哪一叠
 * 是同一叠,不会出现"放下去的是这一叠、扣的是那一叠"。
 *
 * <p>两叠算同一种只看物品与全部组件({@link ItemStack#isSameItemSameComponents})。只说了 id、而背包里有几种不同的叠时,
 * 取确定的一叠:<b>主手那一格先,其余按格号</b>——主手拿着哪一叠就是她指的哪一叠(真玩家拿着东西右键也是用手里这一叠),要用另一叠,
 * 先用 {@code numen.gui.move} 把它挪进主手那一格。不按内容猜"哪一叠更像她要的"。清单({@link #kinds})把这几种分开列出来,
 * 回执说用了哪一叠,拿不拿哪一叠由模型定。
 */
public final class Stacks {

    private Stacks() {}

    /**
     * 选中的一种。
     *
     * @param slots  主背包里与 {@link #sample} 一模一样的各叠所在的格,选中的那一叠的格排第一
     * @param sample 选中的那一叠,一件(组件原样)
     * @param count  这一种一共几件
     */
    public record Pick(List<Integer> slots, ItemStack sample, int count) {

        /** 选中的那一叠所在的格。 */
        public int slot() {
            return slots.get(0);
        }
    }

    /** 主背包里 {@code item} 的哪一种:见类说明的次序;一件都没有为空。副手与盔甲格不在内。 */
    public static Optional<Pick> pick(Inventory inventory, Item item) {
        List<Integer> order = order(inventory);
        for (int first : order) {
            ItemStack chosen = inventory.items.get(first);
            if (chosen.isEmpty() || !chosen.is(item)) {
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            int count = 0;
            for (int slot : order) {
                ItemStack there = inventory.items.get(slot);
                if (ItemStack.isSameItemSameComponents(chosen, there)) {
                    slots.add(slot);
                    count += there.getCount();
                }
            }
            return Optional.of(new Pick(List.copyOf(slots), chosen.copyWithCount(1), count));
        }
        return Optional.empty();
    }

    /** 查叠的次序:主手那一格,再是所有格按格号。 */
    private static List<Integer> order(Inventory inventory) {
        List<Integer> order = new ArrayList<>();
        order.add(inventory.selected);
        for (int i = 0; i < inventory.items.size(); i++) {
            if (i != inventory.selected) {
                order.add(i);
            }
        }
        return order;
    }

    /** 一种东西与它的件数。 */
    public record Kind(ItemStack sample, int count) {}

    /** 一堆叠按"物品与组件都相同"合成几种,先见先列;空叠不算。 */
    public static List<Kind> kinds(Iterable<ItemStack> stacks) {
        List<ItemStack> samples = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            int at = 0;
            while (at < samples.size() && !ItemStack.isSameItemSameComponents(samples.get(at), stack)) {
                at++;
            }
            if (at == samples.size()) {
                samples.add(stack.copyWithCount(1));
                counts.add(stack.getCount());
            } else {
                counts.set(at, counts.get(at) + stack.getCount());
            }
        }
        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            kinds.add(new Kind(samples.get(i), counts.get(i)));
        }
        return kinds;
    }
}
