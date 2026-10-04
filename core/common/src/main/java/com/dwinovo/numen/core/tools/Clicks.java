package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.sdk.BlockAt;
import com.dwinovo.numen.sdk.Doc;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * 按一下交回的值:{@code numen.use.*} 的结果。右键打开了一个界面交回那个界面({@link GuiOps.Window}),别的点击交回按了哪个键、瞄哪一格、
 * 变了什么。
 */
public final class Clicks {

    private Clicks() {}

    /** 鼠标的哪个键。 */
    public enum Button { LEFT, RIGHT }

    /** 右键一格({@code numen.use.block})的结果:打开的界面,或点了什么、变了什么。 */
    public sealed interface Pressed permits GuiOps.Window, Clicked {}

    /** 左键一下({@code numen.use.hit})的结果:点一格的,或点一只实体的。 */
    public sealed interface Hit permits Clicked, EntityClicked {}

    /** 点一格(或朝前方)的结果。 */
    @Doc("What a click did.")
    public record Clicked(Button button,
                          @Doc("The cell aimed at.") Optional<BlockPos> aim,
                          @Doc("The block the click used, when it opened or worked a station.") Optional<BlockAt> block,
                          @Doc("What changed: your inventory, health and riding, the block, new entities; empty means the click did nothing.")
                          List<String> changes) implements Pressed, Hit {}

    /** 点一只实体的结果。 */
    @Doc("What a click on an entity did.")
    public record EntityClicked(Button button,
                                int entityId,
                                @Doc("What changed; empty means the click did nothing.") List<String> changes)
            implements Hit {}
}
