package com.dwinovo.numen.tools;

import com.dwinovo.numen.act.MenuOrigin;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 在打开的界面里搬东西的点击:一次一步({@link Move}),整叠挪到另一边(像按住 Shift 点它),或往指定的一格里放几件。
 * 搬哪一格、搬几件由 {@code GuiItemsCompanionTask} 定;从容器里拿东西的那一步点下去之前要过权限层,那个动作在 {@link #taking}。
 */
public final class ContainerOps {

    private ContainerOps() {}

    /**
     * 搬一步。
     *
     * @param to    放到哪一格;null 是整叠挪到另一边
     * @param count 往 {@code to} 里放几件;整叠挪到另一边时不用,是 null
     */
    public record Move(int from, Integer to, Integer count) {}

    /**
     * 这一步会不会从容器里拿东西进她的背包:会就是那个 {@code take} 动作,不会是 null。
     *
     * <p>拿 = 东西离开界面里不是她背包的那一段,落进她的背包。右键没有方块实体的方块(工作台、切石机)开的界面里,格子里只有她自己
     * 刚放进去的东西,不算。容器是哪一格从 {@link MenuOrigin} 取;右键实体开的,动作不带格子。
     */
    public static Action taking(Move m, NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        Slot from = menu.slots.get(m.from());
        if (mine(from, self) || (m.to() != null && !mine(menu.slots.get(m.to()), self))) {
            return null;
        }
        BlockPos block = MenuOrigin.block(self);
        if (block == null) {
            return Action.take(null, null, from.getItem().getItem());
        }
        BlockState state = self.level().getBlockState(block);
        return state.hasBlockEntity() ? Action.take(block, state, from.getItem().getItem()) : null;
    }

    private static boolean mine(Slot slot, NumenPlayer self) {
        return slot.container == self.getInventory();
    }

    /** 点下去,做这一步。搬没搬动、搬了几件由调用方读回来:界面可能不收(放不下、那一格不收这种东西)。 */
    public static void step(Move m, NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (m.to() != null) {
            MenuOps.dripInto(menu, self, m.from(), m.to(), m.count());
        } else if (menu instanceof LecternMenu) {
            // 讲台的书按住 Shift 点不出来:原版只认界面上"取书"那个钮
            self.packets().menuButton(LecternMenu.BUTTON_TAKE_BOOK);
        } else {
            menu.clicked(m.from(), 0, ClickType.QUICK_MOVE, self);
        }
    }
}
