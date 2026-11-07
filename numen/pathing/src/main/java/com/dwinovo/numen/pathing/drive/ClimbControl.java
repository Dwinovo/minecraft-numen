package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 攀爬:往上是在可攀爬的格里按住跳(原版按住跳就往上爬,藤蔓不必贴墙),往下是顺着滑下去(爬进脚手架要按住潜行);水平方向守在这一列
 * 正中。头顶要开的活板门、要挖的格先做:挂在梯子上做改动时按住潜行,原版攀爬时潜行不往下滑。
 *
 * <p>落点是站着的(脚手架每一格顶上的板托得住人,落点站在下面那一格的板上)时,一步走完要稳稳站在板上才认({@link Tracker}):
 * 往上爬到了落点的高度就松开跳,冲劲再带身子升一小截,落回板上;往下穿过起步那块板之后就松开潜行,身子落到下面那块板上停住。
 * 一直按着的话身子一路穿过落点,走不完这一步。
 */
final class ClimbControl extends Control {

    /** 往下穿过起步那块板之后,脚低于板顶这么多就松开潜行:板只在身体在它上面时托着,脚已在板顶之下,板就不再挡路。 */
    private static final double PAST_PLATE = 0.1;

    ClimbControl(Rig rig, Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.SPRINT);
        boolean up = m.to().getY() > m.from().getY();
        boolean onPlate = m.landing().grounded();
        double feet = rig.entity.getY();
        // 脚手架每一格顶上的板托得住人,只有潜行(原版碰撞上下文里的"在下降")才让开:往下爬进脚手架要按住潜行
        keys().set(Key.SNEAK, !up && rig.world().getBlockState(m.to()).is(Blocks.SCAFFOLDING)
                && (!onPlate || feet > m.start().feetY() - PAST_PLATE));
        keys().set(Key.JUMP, up && (!onPlate || feet < m.landing().feetY()));
        Vec3 c = center(m.to());
        Steering.stop(rig, c.x, c.z, m.landing().feetY());
        return Beat.IDLE;
    }

    @Override
    boolean falls() {
        return m.to().getY() < m.from().getY();
    }
}
