package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * "背包里的哪一叠":模型说的是物品 id,或点名清单里的一种({@code numen.inv.items()} 的一项);身上的是一叠叠带组件的真东西
 * (装着钻石的潜影盒、附魔的镐、不同的药水)。<b>把 id 或点名的一种落到具体一叠只在这里</b>——手放、直接写、拿到手上、丢出去、吃、
 * 穿戴、往界面里放都问它,放哪一叠、扣哪一叠、回执报哪一叠是同一叠,不会出现"放下去的是这一叠、扣的是那一叠"。
 *
 * <p>两叠算同一种只看物品与全部组件({@link #sameKind})。要哪一种由 {@link Which} 说。只说了 id、而背包里有几种不同的叠时,
 * 取确定的一叠:<b>主手那一格先,其余按格号</b>——主手拿着哪一叠就是她指的哪一叠(真玩家拿着东西右键也是用手里这一叠),
 * 不按内容猜"哪一叠更像她要的";点名了一种({@link Which#exactly})就只认那一种的叠,次序相同。清单({@link #kinds})把这几种
 * 分开列出来,回执说用了哪一叠。
 */
public final class Stacks {

    private Stacks() {}

    /** 两叠是不是同一种:物品与全部组件都相同。选叠、合种、丢、穿戴比的都是它,"什么算一种"只此一处。 */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * 一种的记号:同一种({@link #sameKind})的叠记号相同,从物品与全部组件算出来,和件数、在哪一格无关。清单里的每一项带着它,
     * 交回来就凭它认回那一种({@link #carried})。只在这一次开服里作数。
     */
    public static String code(ItemStack stack) {
        return String.format("%08x", ItemStack.hashItemAndComponents(stack));
    }

    /** 主背包里这种物品、记号是 {@code code} 的那一种的一叠;她已经没有这一种了为空。 */
    public static Optional<ItemStack> carried(Inventory inventory, Item item, String code) {
        for (ItemStack stack : inventory.items) {
            if (stack.is(item) && code(stack).equals(code)) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

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

    /**
     * 要她带着的哪一种。
     *
     * @param item  物品
     * @param exact 只认与它同一种({@link #sameKind})的叠,一件(组件原样);这种物品的哪一叠都行为 null
     */
    public record Which(Item item, ItemStack exact) {

        /** 这种物品的哪一叠都行。 */
        public static Which of(Item item) {
            return new Which(item, null);
        }

        /** 只认与 {@code sample} 同一种的叠。 */
        public static Which exactly(ItemStack sample) {
            return new Which(sample.getItem(), sample.copyWithCount(1));
        }

        /** 这一叠是不是要的那一种。 */
        public boolean matches(ItemStack stack) {
            return exact == null ? stack.is(item) : sameKind(exact, stack);
        }
    }

    /** 主背包里 {@code which} 要的哪一种:见类说明的次序;一件都没有为空。副手与盔甲格不在内。 */
    public static Optional<Pick> pick(Inventory inventory, Which which) {
        List<Integer> order = order(inventory);
        for (int first : order) {
            ItemStack chosen = inventory.items.get(first);
            if (chosen.isEmpty() || !which.matches(chosen)) {
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            int count = 0;
            for (int slot : order) {
                ItemStack there = inventory.items.get(slot);
                if (sameKind(chosen, there)) {
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
            while (at < samples.size() && !sameKind(samples.get(at), stack)) {
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
