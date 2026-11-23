package com.dwinovo.numen.tools;

import com.dwinovo.numen.act.Interaction;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * 按一下交回的值:{@code numen.use.*} 的结果。点一下(格子、空气、实体)打开了一个界面交回那个界面({@link GuiOps.Window}),别的点击交回
 * 按了哪个键、点了什么、游戏接没接。这一下之后世界变成了什么样不在这里:她要知道就自己看({@code numen.scan}、{@code numen.inv})。
 */
public final class Clicks {

    private Clicks() {}

    /** 鼠标的哪个键。 */
    public enum Button { LEFT, RIGHT }

    /**
     * 点一下({@code numen.use.block}、{@code item}、{@code entity}、{@code hit})的结果,点的是格子、空气还是实体都一样:
     * 打开了一个界面,交回那个界面;否则是点了什么、游戏接没接。
     */
    public sealed interface Pressed permits GuiOps.Window, Clicked {}

    /** 点一格、一只实体或朝前方的结果。 */
    @Doc("What a click did.")
    public record Clicked(Button button,
                          @Doc("The cell aimed at.") Optional<BlockPos> aim,
                          @Doc("The entity the click landed on, by its id.") Optional<String> entityId,
                          @Doc("The block the click used, when it opened or worked a station.") Optional<BlockAt> block,
                          @Doc("Whether the game accepted the click. A right click: the block, the entity or what you "
                                  + "hold acted on it. A left click: the block broke, or the hit landed on the entity. "
                                  + "It says nothing about what changed afterwards.")
                          boolean accepted) implements Pressed {}

    /**
     * 一次点击交回的值:这一下打开了界面({@code opened})就是那个界面(点开它的是 {@code station} 那一格时连同那一格),
     * 否则是 {@link Clicked}。点格子、点空气、点实体的任务都从这里交回值。
     */
    public static Pressed result(NumenPlayer player, boolean opened, Button button, BlockPos aim, String entityId,
                                 Optional<BlockAt> station, boolean accepted) {
        if (opened) {
            GuiOps.Window window = GuiOps.window(player);
            return station.map(window::openedAt).orElse(window);
        }
        return new Clicked(button, Optional.ofNullable(aim), Optional.ofNullable(entityId), station, accepted);
    }

    /**
     * 按完之后回执里接的那半句:这一下游戏接没接,写成给模型读的字,只此一处。右键说游戏交回的话,左键说那一格碎没碎、那一下落没落;
     * 右键一下都没按到是空串。
     */
    public static String said(Interaction pressed) {
        if (pressed.button() == Interaction.Button.USE) {
            Mouse.Use.Answer answer = pressed.answer();
            if (answer == null) {
                return "";
            }
            return switch (answer) {
                case TAKEN -> " — accepted";
                case FAILED -> " — not accepted: it cannot be done there";
                case IGNORED -> " — not accepted: nothing there responds to this click";
            };
        }
        if (pressed.onEntity()) {
            return pressed.accepted() ? " — the hit landed" : " — the hit did not land";
        }
        return pressed.accepted() ? " — it broke" : " — it did not break";
    }
}
