package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 身体此刻开着的界面是右键哪儿打开的。界面本身不记得自己属于哪一格——箱子、熔炉、模组机器各有各的容器
 * 实现——所以在右键的落点({@link Interaction})记一笔:从容器里拿东西是对那一格做的事,权限层要知道是哪一格。
 *
 * <p>跟着身体走({@link NumenPlayer#state}),只认记下时的那个界面:界面关了、换了,记录就不作数。
 */
public final class MenuOrigin {

    private AbstractContainerMenu menu;
    /** 右键打开它的方块;右键实体打开的是 null。 */
    private BlockPos block;

    private MenuOrigin() {}

    /**
     * 右键落点在按下之后调:这一下打开了新界面就记下它从哪儿来。
     *
     * @param before 按下之前开着的界面
     * @param block  右键的方块;右键实体传 null
     */
    public static void pressed(NumenPlayer body, AbstractContainerMenu before, BlockPos block) {
        if (body.containerMenu != before && body.containerMenu != body.inventoryMenu) {
            MenuOrigin origin = body.state(MenuOrigin.class, MenuOrigin::new);
            origin.menu = body.containerMenu;
            origin.block = block == null ? null : block.immutable();
        }
    }

    /** 此刻开着的界面是不是右键打开的。 */
    public static boolean known(NumenPlayer body) {
        MenuOrigin origin = body.state(MenuOrigin.class, MenuOrigin::new);
        return origin.menu != null && origin.menu == body.containerMenu;
    }

    /** 此刻开着的界面是右键哪一格打开的;右键实体打开的、或来历不明的是 null。 */
    public static BlockPos block(NumenPlayer body) {
        MenuOrigin origin = body.state(MenuOrigin.class, MenuOrigin::new);
        return known(body) ? origin.block : null;
    }
}
