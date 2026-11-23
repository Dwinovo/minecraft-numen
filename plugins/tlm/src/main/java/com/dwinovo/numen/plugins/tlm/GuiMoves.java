package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.entity.Stacks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 在她开着的界面里按格子搬一种东西,和人用鼠标一样:左键拿起整叠、左键放下光标上的全部、右键放下一件。一格收不收、收几件由那
 * 一格自己说({@link Slot#mayPlace}、{@link Slot#getMaxStackSize(ItemStack)}),放下、取走时界面给那一格挂的后果(女仆的饰品
 * 生效、背包变化的事件)也就照样发生。
 *
 * <p>只碰原版的菜单,不认这是谁的界面。拆叠只在她自己的格子上做(那里点多少下都没有后果),界面那一侧的格子每格只左键一下。
 */
final class GuiMoves {

    private GuiMoves() {}

    /** 把她背包里 {@code which} 要的那一种搬进 {@code into} 这几格,至多 {@code count} 件;先并进已有的同一种,再放空格。 */
    static void give(ServerPlayer her, Stacks.Which which, int count, List<Slot> into) {
        int left = count;
        for (Slot own : own(her)) {
            if (left <= 0) {
                return;
            }
            if (!which.matches(own.getItem())) {
                continue;
            }
            int have = own.getItem().getCount();
            int want = Math.min(left, have);
            click(her, own, false);
            put(her, own, have - want);
            for (Slot slot : stackedFirst(into)) {
                put(her, slot, Math.min(room(slot, carried(her)), carried(her).getCount()));
            }
            int over = want - carried(her).getCount();
            put(her, own, carried(her).getCount());
            left -= over;
            if (over < want) {
                return;
            }
        }
    }

    /** 把 {@code from} 这几格里的 {@code item} 搬进她的背包,至多 {@code count} 件;一格里多出来的留在原处。 */
    static void take(ServerPlayer her, Item item, int count, List<Slot> from) {
        int left = count;
        for (Slot slot : from) {
            if (left <= 0) {
                return;
            }
            if (!slot.getItem().is(item) || !slot.mayPickup(her)) {
                continue;
            }
            click(her, slot, false);
            int picked = carried(her).getCount();
            int keep = picked - Math.min(left, picked);
            for (Slot own : stackedFirst(own(her))) {
                put(her, own, Math.min(room(own, carried(her)), carried(her).getCount() - keep));
            }
            int got = picked - carried(her).getCount();
            put(her, slot, carried(her).getCount());
            left -= got;
            if (got < picked - keep) {
                return;
            }
        }
    }

    /** 这几格一共还放得下几件 {@code stack}。 */
    static int room(List<Slot> slots, ItemStack stack) {
        int room = 0;
        for (Slot slot : slots) {
            room += room(slot, stack);
        }
        return room;
    }

    /** 她背包里 {@code which} 要的第一叠(开着的界面里她自己的那些格);没有是空的。 */
    static ItemStack carriedStack(ServerPlayer her, Stacks.Which which) {
        for (Slot own : own(her)) {
            if (which.matches(own.getItem())) {
                return own.getItem();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 开着的界面里她自己背包的那些格。 */
    static List<Slot> own(ServerPlayer her) {
        List<Slot> own = new ArrayList<>();
        for (Slot slot : her.containerMenu.slots) {
            if (slot.container == her.getInventory()) {
                own.add(slot);
            }
        }
        return own;
    }

    /** 这一格还放得下几件 {@code stack}:不收它、放着别的东西、满了都是 0。 */
    private static int room(Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !slot.mayPlace(stack)) {
            return 0;
        }
        ItemStack in = slot.getItem();
        if (in.isEmpty()) {
            return slot.getMaxStackSize(stack);
        }
        return ItemStack.isSameItemSameComponents(in, stack) ? Math.max(0, slot.getMaxStackSize(in) - in.getCount()) : 0;
    }

    /** 放着东西的格在前、空格在后,各自保持原来的先后。 */
    private static List<Slot> stackedFirst(List<Slot> slots) {
        List<Slot> ordered = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.hasItem()) {
                ordered.add(slot);
            }
        }
        for (Slot slot : slots) {
            if (!slot.hasItem()) {
                ordered.add(slot);
            }
        }
        return ordered;
    }

    /** 把光标上的东西往这一格放 {@code n} 件:是光标上的全部就左键一下,不是就右键 {@code n} 下。调用的人保证这一格放得下。 */
    private static void put(ServerPlayer her, Slot slot, int n) {
        if (n <= 0) {
            return;
        }
        if (n >= carried(her).getCount()) {
            click(her, slot, false);
            return;
        }
        for (int i = 0; i < n; i++) {
            click(her, slot, true);
        }
    }

    private static ItemStack carried(ServerPlayer her) {
        return her.containerMenu.getCarried();
    }

    private static void click(ServerPlayer her, Slot slot, boolean right) {
        her.containerMenu.clicked(slot.index, right ? 1 : 0, ClickType.PICKUP, her);
    }
}
