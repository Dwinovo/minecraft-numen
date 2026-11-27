package com.dwinovo.numen.plugins.minecraft.tools;

import com.dwinovo.numen.plugins.minecraft.act.Interaction;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * 按一下交回的值:{@code minecraft.use.*} 的结果,按了哪个键、点了什么、游戏接没接。这一下之后世界变成了什么样不在这里:她要知道就自己看
 * ({@code minecraft.scan}、{@code minecraft.inv})。
 */
public final class Clicks {

    private Clicks() {}

    /** 鼠标的哪个键。 */
    public enum Button { LEFT, RIGHT }

    /** 点一下({@code minecraft.use.block}、{@code item}、{@code entity}、{@code hit})的结果,点的是格子、空气还是实体都一样。 */
    @Doc("What a click did.")
    public record Clicked(Button button,
                          @Doc("The cell aimed at.") Optional<BlockPos> aim,
                          @Doc("The entity the click landed on, by its id.") Optional<String> entityId,
                          @Doc("The block the click used, when it opened or worked a station.") Optional<BlockAt> block,
                          @Doc("Whether the game accepted the click. A right click: the block, the entity or what you "
                                  + "hold acted on it. A left click: the block broke, or the hit landed on the entity. "
                                  + "It says nothing about what changed afterwards.")
                          boolean accepted) {}

    /** 一次点击交回的值。点格子、点空气、点实体的任务都从这里交回值。 */
    public static Clicked result(Button button, BlockPos aim, String entityId, Optional<BlockAt> station,
                                 boolean accepted) {
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
