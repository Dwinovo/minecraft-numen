package com.dwinovo.numen.core.tools;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/**
 * 菜单点击原语。精确挪 N 个的"抓起-逐个右键-归还余数"序列此前在
 * transfer 与 craft 摆料里各抄一份,点击时序一旦分叉,两处的"精确
 * 数量"语义就会各自漂移——序列只此一份。
 */
public final class MenuOps {

    private MenuOps() {}

    /**
     * 从 {@code from} 抓起整叠,向 {@code to} 逐个放最多 {@code count} 次
     * (右键单放,自动合并/填充),余数放回 {@code from}。放的次数按实际
     * 抓起数钳制——光标一旦放空,多余的右键会反手抓起目标格的半叠。
     * 调用方自己核对落位数量:菜单可能拒收(装不下/槽位过滤)。
     */
    public static void dripInto(AbstractContainerMenu menu, Player who, int from, int to, int count) {
        menu.clicked(from, 0, ClickType.PICKUP, who);            // grab the stack
        int drops = Math.min(count, menu.getCarried().getCount());
        for (int i = 0; i < drops; i++) {
            menu.clicked(to, 1, ClickType.PICKUP, who);          // drop ONE (merges / fills)
        }
        if (!menu.getCarried().isEmpty()) {
            menu.clicked(from, 0, ClickType.PICKUP, who);        // return the remainder
        }
    }
}
