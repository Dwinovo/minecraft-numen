package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.LecternMenu;

/**
 * 在打开的界面里搬东西的点击:一次一步({@link Move}),整叠挪到另一边(像按住 Shift 点它),或往指定的一格里放几件。
 * 搬哪一格、搬几件由 {@code GuiItemsCompanionTask} 定。
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
