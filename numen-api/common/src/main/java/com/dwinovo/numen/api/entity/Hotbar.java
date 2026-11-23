package com.dwinovo.numen.api.entity;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 把东西拿到手上,照原版玩家的几个按键:数字键切换快捷栏选中的格({@code handleSetCarriedItem}),中键把背包深处的
 * 东西换进快捷栏({@code handlePickItem},换进的是原版挑的那一格——先空格,再不是附魔过的,原来那件换回背包,不丢),
 * F 键交换主副手,创造模式中键凭空取一叠。每一次真的换了都交回一个 {@link BodyAction},由调用方记进结局。同伴的是
 * {@link NumenPlayer#hotbar}。
 */
public final class Hotbar {

    /** 副手在 {@link BodyAction.Held#from} 里的编号(原版物品栏里副手那一格)。 */
    public static final int OFFHAND = Inventory.SLOT_OFFHAND;

    private final ServerPlayer body;
    private final ClientPackets packets;

    public Hotbar(ServerPlayer body) {
        this.body = body;
        this.packets = new ClientPackets(body);
    }

    /**
     * 把主背包第 {@code slot} 格的东西拿到主手;{@code slot} 是 {@link #OFFHAND} 就交换主副手;{@code slot} 为负是空手——切到
     * 快捷栏里一个空格({@link #emptyHand}),没有空格就拿着手上的东西。已经在主手上就什么也不做。手上本来就空着、只是换到
     * 另一个空格时,手上拿的没变,不算一个身体动作。
     */
    public Optional<BodyAction> hold(int slot) {
        Inventory inventory = body.getInventory();
        if (slot == OFFHAND) {
            ItemStack offhand = body.getOffhandItem().copyWithCount(1);
            packets.playerAction(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO,
                    Direction.DOWN, 0);
            return Optional.of(new BodyAction.Held(offhand, OFFHAND, inventory.selected));
        }
        if (slot < 0) {
            int empty = emptyHand(inventory);
            if (empty < 0 || empty == inventory.selected) {
                return Optional.empty();
            }
            boolean wasEmpty = inventory.getSelected().isEmpty();
            BodyAction action = select(empty, ItemStack.EMPTY);
            return wasEmpty ? Optional.empty() : Optional.of(action);
        }
        if (slot == inventory.selected) {
            return Optional.empty();
        }
        ItemStack stack = inventory.getItem(slot).copyWithCount(1);
        if (Inventory.isHotbarSlot(slot)) {
            return Optional.of(select(slot, stack));
        }
        packets.pickItem(slot);
        return Optional.of(new BodyAction.Held(stack, slot, inventory.selected));
    }

    /**
     * 让一只手拿着 {@code which} 要的那一种,右键就能用上它:主手已经是它,或主手空着而副手是它(原版右键主手用不了就试副手),
     * 什么也不做;背包里有就拿到主手(拿哪一叠由 {@link Stacks#pick} 定);只在副手、主手又占着,就交换主副手;创造模式背包里没有就
     * 凭空取一叠——只在哪一叠都行时,点名的一种不在身上取不出来。
     *
     * @return 拿到了没有、拿在哪只手上,连同做了什么(没做为 null)
     */
    public Grip grip(Stacks.Which which) {
        Inventory inventory = body.getInventory();
        ItemStack main = inventory.getSelected();
        if (which.matches(main)) {
            return new Grip(InteractionHand.MAIN_HAND, null);
        }
        if (main.isEmpty() && which.matches(body.getOffhandItem())) {
            return new Grip(InteractionHand.OFF_HAND, null);
        }
        Optional<Stacks.Pick> pick = Stacks.pick(inventory, which);
        if (pick.isPresent()) {
            return new Grip(InteractionHand.MAIN_HAND, hold(pick.get().slot()).orElse(null));
        }
        if (which.matches(body.getOffhandItem())) {
            return new Grip(InteractionHand.MAIN_HAND, hold(OFFHAND).orElse(null));
        }
        if (which.exact() == null && body.gameMode.isCreative()) {
            inventory.setPickedItem(new ItemStack(which.item()));
            return new Grip(InteractionHand.MAIN_HAND, new BodyAction.Conjured(which.item(), inventory.selected));
        }
        return new Grip(null, null);
    }

    /** {@link #grip} 够得着的地方——主背包与副手——里 {@code which} 要的那一种一共几件。 */
    public int count(Stacks.Which which) {
        int n = 0;
        for (ItemStack stack : body.getInventory().items) {
            if (which.matches(stack)) {
                n += stack.getCount();
            }
        }
        ItemStack offhand = body.getOffhandItem();
        return which.matches(offhand) ? n + offhand.getCount() : n;
    }

    /**
     * {@link #grip} 的结果。
     *
     * @param hand   拿着它的那只手;没拿到为 null
     * @param action 为此做了什么;什么也没做为 null
     */
    public record Grip(InteractionHand hand, BodyAction action) {

        /** 拿到了。 */
        public boolean ready() {
            return hand != null;
        }
    }

    /**
     * 空手用快捷栏的哪一格。捡起的东西先进背包的第一个空格;空手挖着的时候那一格被塞进东西,手上就换了一样东西,原版会从头挖。
     * 所以挑一个不是第一个空格的空格:手上正空着的那一格是就不动,不是就从后往前找;只有第一个空格可用时用它。没有空格为 -1。
     */
    private static int emptyHand(Inventory inventory) {
        int first = inventory.getFreeSlot();
        if (inventory.getSelected().isEmpty() && inventory.selected != first) {
            return inventory.selected;
        }
        for (int i = Inventory.getSelectionSize() - 1; i >= 0; i--) {
            if (inventory.getItem(i).isEmpty() && i != first) {
                return i;
            }
        }
        return first >= 0 && Inventory.isHotbarSlot(first) ? first : -1;
    }

    private BodyAction select(int slot, ItemStack stack) {
        packets.setCarriedItem(slot);
        return new BodyAction.Held(stack, slot, slot);
    }
}
