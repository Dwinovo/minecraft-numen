package com.dwinovo.numen.api.task.chain;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.reflex.Reflex;
import net.minecraft.tags.FluidTags;

/**
 * 水里的漂浮——原版生物的漂浮行为({@code FloatGoal}/{@code Swim}),身体的基本面。假玩家没有客户端替它按跳,在水里不按键只会慢慢沉下去。
 *
 * <p>条件照原版 {@code Swim}:在水里、水没过身体的高度超过跳跃阈值({@code getFluidJumpThreshold})时按住跳。另加车万女仆
 * {@code MaidSwimJumpTask} 的两条:整个人已经在水下({@code isUnderWater},眼睛没进水里才算浮着)时不被动上浮——刚潜到水底某一格、
 * 下一个工具调用还没来,不能自己浮上去(浮上来是换气本能的事,它只管真缺氧);寻路不插手。
 *
 * <p>它不是哪一层的出价,而是每刻在谁拿了身体之后补的一手({@code CompanionBrain}):判"有没有人在动身体"用键盘本身——这一刻没有
 * 任何人按着前后左右、跳、潜行(寻路走着时每刻都按着键;等待、站着干活的任务不按),才轮到它按跳。手上正挖着一块(鼠标按着挖掘)同样算在动身体:跳一下准星就晃,挖掘进度清零。键盘是所有驱动者共用的那一处,
 * 所以不必在每个任务里各加一份,也不必区分是寻路、任务还是反射。它自己按的跳在下一刻开头先松掉,免得被当成别人按的。
 * 不发任何事件:浮着是身体的常态,不是值得说的事。
 */
public final class FloatChain implements Reflex {

    /** 有人在驱动身体的键:移动、跳、潜行(潜行是寻路往下沉用的)。疾跑不单独算,它总跟着前进。 */
    private static final Controls.Key[] DRIVING = {Controls.Key.FORWARD, Controls.Key.BACK, Controls.Key.LEFT,
            Controls.Key.RIGHT, Controls.Key.JUMP, Controls.Key.SNEAK};

    /** 上一刻的跳是不是自己按的。 */
    private boolean pressed;

    /** 这一刻开头:松掉自己上一刻按的跳。 */
    public void release(NumenPlayer companion) {
        if (pressed) {
            companion.controls().release(Controls.Key.JUMP);
            pressed = false;
        }
    }

    /** 这一刻谁拿了身体之后:没人在按驱动键、人又在水面附近,就按住跳。 */
    public void assist(NumenPlayer companion) {
        for (Controls.Key key : DRIVING) {
            if (companion.controls().held(key)) {
                return;
            }
        }
        if (companion.mouse().digging()) {
            return;   // 手上正挖着:跳起落落会晃掉准星,挖掘进度跟着清零
        }
        if (companion.isInWater() && !companion.isUnderWater()
                && companion.getFluidHeight(FluidTags.WATER) > companion.getFluidJumpThreshold()) {
            companion.controls().press(Controls.Key.JUMP);
            pressed = true;
        }
    }

    @Override
    public String id() {
        return "float";
    }

    @Override
    public String describe() {
        return "在水里不动手时浮在水面上不往下沉;整个人已经在水下时不会自己浮上来";
    }
}
