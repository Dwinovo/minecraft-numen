package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * 按一下交回的值:{@code numen.use.*} 的结果。点一下(格子、空气、实体)打开了一个界面交回那个界面({@link GuiOps.Window}),别的点击交回
 * 按了哪个键、点了什么、变了什么。
 */
public final class Clicks {

    private Clicks() {}

    /** 鼠标的哪个键。 */
    public enum Button { LEFT, RIGHT }

    /**
     * 点一下({@code numen.use.block}、{@code item}、{@code entity}、{@code hit})的结果,点的是格子、空气还是实体都一样:
     * 打开了一个界面,交回那个界面;否则是点了什么、变了什么。
     */
    public sealed interface Pressed permits GuiOps.Window, Clicked {}

    /** 点一格、一只实体或朝前方的结果。 */
    @Doc("What a click did.")
    public record Clicked(Button button,
                          @Doc("The cell aimed at.") Optional<BlockPos> aim,
                          @Doc("The entity the click landed on, by its id.") Optional<Integer> entityId,
                          @Doc("The block the click used, when it opened or worked a station.") Optional<BlockAt> block,
                          @Doc("What changed: your inventory, health and riding, the block, new entities; empty means the click did nothing.")
                          List<String> changes) implements Pressed {}

    /**
     * 一次点击交回的值:这一下打开了界面({@code opened})就是那个界面(点开它的是 {@code station} 那一格时连同那一格),
     * 否则是 {@link Clicked}。点格子、点空气、点实体的任务都从这里写回执。
     */
    public static Pressed result(NumenPlayer player, boolean opened, Button button, BlockPos aim, Integer entityId,
                                 Optional<BlockAt> station, List<String> changes) {
        if (opened) {
            GuiOps.Window window = GuiOps.window(player);
            return station.map(window::openedAt).orElse(window);
        }
        return new Clicked(button, Optional.ofNullable(aim), Optional.ofNullable(entityId), station, List.copyOf(changes));
    }
}
