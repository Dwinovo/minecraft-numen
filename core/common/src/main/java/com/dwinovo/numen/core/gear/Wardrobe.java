package com.dwinovo.numen.core.gear;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 穿、脱、自动选位——身体上"把这件穿上 / 摘下来"的唯一实现。它不认识任何入口:{@code equip_item} 工具只把
 * 参数翻译过来交给这里,别的入口也直接调这里。
 *
 * <p>穿戴位置只经 {@link NumenPlugins#gearSlots} 与 {@link NumenPlugins#gearKinds} 来:原版四件甲和模组的
 * 饰品栏是同一扇门里的两个来源,这里一个都不认识。两只手不是穿戴位置——主手是在 36 格里选中一格,副手是
 * 另一只手握着的东西——所以 {@code mainhand}、{@code offhand} 在这里单独认。
 *
 * <p>货源只在背包的 36 格里找:身上穿着的、副手握着的不是"背包里的这件"。东西只在背包和身上之间搬,绝不
 * 掉在地上——放不下就在动手之前拒绝。只在服务端主线程调用。
 */
public final class Wardrobe {

    public static final String MAINHAND = "mainhand";
    public static final String OFFHAND = "offhand";
    /** 卸下时的别名:原版四件甲。 */
    public static final String ARMOR = "armor";

    private Wardrobe() {}

    /**
     * 一次穿脱的结论。
     *
     * @param ok      成功与否
     * @param message 给模型看的那一句
     * @param failure 失败的归类;成功时为 {@code null}
     * @param data    回执里的结构化字段
     */
    public record Outcome(boolean ok, String message, FailureType failure, Map<String, Object> data) {

        static Outcome done(String message, Map<String, Object> data) {
            return new Outcome(true, message, null, data);
        }

        static Outcome failed(String message, FailureType failure, Map<String, Object> data) {
            return new Outcome(false, message, failure, data);
        }
    }

    /** 卸下时一个 {@code slot} 指的是哪些槽名:{@code armor} 展开成原版四件甲,其余就是它自己。 */
    public static List<String> namesFor(String slot) {
        return ARMOR.equals(slot) ? VanillaArmor.NAMES : List.of(slot);
    }

    // ---------------------------------------------------------------------
    // 穿
    // ---------------------------------------------------------------------

    /**
     * 把背包里的一件 {@code item} 穿上或拿到手上。
     *
     * @param slot {@code null} = 自动选位:归某类穿戴位置管的进那类位置,不归任何来源管的交给手(按原版
     *             的规矩,盾这类副手物进副手,其余进主手);否则是 {@code mainhand}、{@code offhand} 或
     *             {@code <worn>} 里的槽名
     */
    public static Outcome wear(NumenPlayer body, Item item, String slot) {
        Inventory inv = body.getInventory();
        String label = label(item);
        Map<String, Object> data = new HashMap<>();
        data.put("item", label);
        int src = carriedSlot(inv, item);
        if (src < 0) {
            return Outcome.failed("no " + label + " in inventory to equip", FailureType.NO_MATERIAL, data);
        }
        ItemStack one = inv.getItem(src).copyWithCount(1);

        String where = slot;
        List<GearSlot> candidates = List.of();
        if (where == null) {
            Set<String> kinds = NumenPlugins.gearKinds(body, one);
            if (kinds.isEmpty()) {
                where = body.getEquipmentSlotForItem(one) == EquipmentSlot.OFFHAND ? OFFHAND : MAINHAND;
            } else {
                candidates = named(body, kinds);
                if (candidates.isEmpty()) {
                    return Outcome.failed(label + " is worn in " + String.join(" or ", kinds)
                            + ", and you have no such slot", FailureType.UNKNOWN, data);
                }
            }
        }
        if (MAINHAND.equals(where)) {
            body.holdInHand(src);
            data.put("slot", MAINHAND);
            return Outcome.done("holding " + label + " in main hand", data);
        }
        if (OFFHAND.equals(where)) {
            candidates = List.of(new OffHand(body));
        } else if (where != null) {
            candidates = named(body, Set.of(where));
            if (candidates.isEmpty()) {
                return Outcome.failed("you have no slot named '" + where + "' — your slots: "
                        + String.join(", ", slotNames(body)), FailureType.UNKNOWN, data);
            }
        }

        // 先找收它的空位;没有空位时,已经戴着同一件就不必换;再找收它、而且原物摘得下的去换
        GearSlot empty = null;
        GearSlot same = null;
        GearSlot swappable = null;
        String refusal = null;
        for (GearSlot s : candidates) {
            Optional<String> no = s.refuseWear(one);
            if (no.isPresent()) {
                if (refusal == null) refusal = no.get();
                continue;
            }
            ItemStack worn = s.worn();
            if (worn.isEmpty()) {
                empty = s;
                break;
            }
            if (worn.is(item)) {
                if (same == null) same = s;
                continue;
            }
            Optional<String> stuck = s.refuseRemove();
            if (stuck.isPresent()) {
                if (refusal == null) refusal = stuck.get();
                continue;
            }
            if (swappable == null) swappable = s;
        }
        if (empty == null && same != null) {
            data.put("slot", same.name());
            return Outcome.done(label + " already equipped in " + same.name(), data);
        }
        GearSlot target = empty != null ? empty : swappable;
        if (target == null) {
            return Outcome.failed(refusal, FailureType.UNKNOWN, data);
        }

        // 换下来的要收回背包:取走的那格若只剩这一件,腾出来的正好放它;否则要有空格,没有就不动手
        ItemStack worn = target.worn();
        if (!worn.isEmpty() && inv.getItem(src).getCount() > 1 && inv.getFreeSlot() < 0) {
            return Outcome.failed("inventory is full — no room to stow " + label(worn.getItem())
                    + " from " + target.name(), FailureType.NO_SPACE, data);
        }
        ItemStack old = target.swap(inv.removeItem(src, 1));
        if (!old.isEmpty()) {
            inv.add(old);
        }
        inv.setChanged();
        data.put("slot", target.name());
        return Outcome.done("equipped " + label + " in " + target.name(), data);
    }

    // ---------------------------------------------------------------------
    // 脱
    // ---------------------------------------------------------------------

    /**
     * 把身上的东西收回背包。每一格依次:先问摘不摘得下,再查背包有没有空格,再摘、收进背包。放不下的、
     * 摘不下的留在身上,如实报。
     *
     * @param slot {@code mainhand}、{@code offhand}、{@code <worn>} 里的槽名或 {@code armor};{@code null} 时
     *             按 {@code item} 找戴着它的第一格
     * @param item 只摘戴着这件的格子;{@code null} = 这些槽里有什么摘什么
     */
    public static Outcome remove(NumenPlayer body, String slot, Item item) {
        if (MAINHAND.equals(slot)) {
            return freeMainHand(body);
        }
        Map<String, Object> data = new HashMap<>();
        String label = slot != null ? slot : label(item);
        List<GearSlot> slots;
        if (OFFHAND.equals(slot)) {
            slots = List.of(new OffHand(body));
        } else if (slot != null) {
            slots = named(body, namesFor(slot));
            if (slots.isEmpty()) {
                return Outcome.failed("you have no slot named '" + slot + "' — your slots: "
                        + String.join(", ", slotNames(body)), FailureType.UNKNOWN, data);
            }
        } else {
            slots = NumenPlugins.gearSlots(body);
        }
        if (item != null) {
            List<GearSlot> wearing = new ArrayList<>();
            for (GearSlot s : slots) {
                if (s.worn().is(item)) wearing.add(s);
            }
            if (wearing.isEmpty()) {
                return Outcome.failed("you are not wearing " + label(item)
                        + (slot != null ? " in " + slot : ""), FailureType.UNKNOWN, data);
            }
            slots = slot != null ? wearing : List.of(wearing.get(0));
        }

        Inventory inv = body.getInventory();
        List<String> removed = new ArrayList<>();
        List<String> noRoom = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<String> stillWorn = new ArrayList<>();
        for (GearSlot s : slots) {
            ItemStack worn = s.worn();
            if (worn.isEmpty()) {
                continue;
            }
            String what = label(worn.getItem()) + " (" + s.name() + ")";
            Optional<String> stuck = s.refuseRemove();
            if (stuck.isPresent()) {
                refused.add(stuck.get());
                stillWorn.add(what);
                continue;
            }
            if (inv.getFreeSlot() < 0) {
                noRoom.add(what);
                stillWorn.add(what);
                continue;
            }
            inv.add(s.swap(ItemStack.EMPTY));
            removed.add(what);
        }
        inv.setChanged();
        if (!removed.isEmpty()) data.put("removed", List.copyOf(removed));
        if (!stillWorn.isEmpty()) data.put("still_worn", List.copyOf(stillWorn));

        if (removed.isEmpty() && stillWorn.isEmpty()) {
            return Outcome.done("nothing to take off — " + label + " already empty", data);
        }
        String refusals = refused.isEmpty() ? "" : String.join("; ", refused);
        if (removed.isEmpty()) {
            return noRoom.isEmpty()
                    ? Outcome.failed(refusals, FailureType.UNKNOWN, data)
                    : Outcome.failed("inventory is full — no room to stow " + label
                            + (refusals.isEmpty() ? "" : "; " + refusals), FailureType.NO_SPACE, data);
        }
        return Outcome.done("took off " + String.join(", ", removed)
                + (noRoom.isEmpty() ? "" : "; inventory full, still wearing " + String.join(", ", noRoom))
                + (refusals.isEmpty() ? "" : "; " + refusals), data);
    }

    /**
     * 腾主手是切换,不是搬运:优先换到空的快捷栏格(真实玩家按数字键,快捷栏布局一格不动),快捷栏全满
     * 才把手持挪进背包的空格。
     */
    private static Outcome freeMainHand(NumenPlayer body) {
        Inventory inv = body.getInventory();
        Map<String, Object> data = new HashMap<>();
        ItemStack held = inv.getItem(inv.selected);
        if (held.isEmpty()) {
            return Outcome.done("nothing to take off — " + MAINHAND + " already empty", data);
        }
        String name = label(held.getItem());
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.selected = i;
                String freed = "main hand freed (switched to an empty hotbar slot, still carrying " + name + ")";
                data.put("removed", List.of(freed));
                return Outcome.done("took off " + freed, data);
            }
        }
        int free = inv.getFreeSlot();   // 快捷栏全满:空位只可能在主背包区
        if (free < 0) {
            data.put("still_worn", List.of(name + " (" + MAINHAND + ")"));
            return Outcome.failed("inventory is full — no room to stow " + MAINHAND, FailureType.NO_SPACE, data);
        }
        inv.setItem(free, held.copy());
        inv.setItem(inv.selected, ItemStack.EMPTY);
        inv.setChanged();
        String freed = "main hand freed (stowed " + name + ")";
        data.put("removed", List.of(freed));
        return Outcome.done("took off " + freed, data);
    }

    // ---------------------------------------------------------------------

    /**
     * 副手。它不是穿戴位置(不进 {@code <worn>},不归任何来源),只借同一副形状,让"换上 / 摘下 / 换下来的
     * 收回背包"对它和对穿戴位置走同一段代码。
     */
    private record OffHand(NumenPlayer body) implements GearSlot {

        @Override
        public String name() {
            return OFFHAND;
        }

        @Override
        public ItemStack worn() {
            return body.getOffhandItem();
        }

        @Override
        public Optional<String> refuseWear(ItemStack one) {
            return Optional.empty();
        }

        @Override
        public Optional<String> refuseRemove() {
            return Optional.empty();
        }

        @Override
        public ItemStack swap(ItemStack in) {
            ItemStack old = body.getOffhandItem();
            body.setItemSlot(EquipmentSlot.OFFHAND, in);
            return old;
        }
    }

    /** 身上叫这些名字的穿戴位置,按来源给出的顺序。 */
    private static List<GearSlot> named(NumenPlayer body, Collection<String> names) {
        List<GearSlot> out = new ArrayList<>();
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            if (names.contains(s.name())) out.add(s);
        }
        return out;
    }

    /** 她实际拥有的槽名:两只手,加上各来源给出的位置(同名只列一次)。 */
    private static List<String> slotNames(NumenPlayer body) {
        Set<String> names = new LinkedHashSet<>(List.of(MAINHAND, OFFHAND));
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            names.add(s.name());
        }
        return List.copyOf(names);
    }

    /** 背包 36 格里第一格放着 {@code item} 的下标;没有是 -1。 */
    private static int carriedSlot(Inventory inv, Item item) {
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(item)) return i;
        }
        return -1;
    }

    private static String label(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
