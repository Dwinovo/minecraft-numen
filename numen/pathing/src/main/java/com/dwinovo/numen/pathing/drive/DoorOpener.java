package com.dwinovo.numen.pathing.drive;

import java.util.function.Function;

import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.Edit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * 开关门的子控制器:任何一种走法、任何一步要穿过门(木门、栅栏门、活板门),都由它开或关。转过去看门身上看得见的一点,
 * 准星落上了就右键一下——不按潜行,门才会响应——门翻过来就做完了。门的另一半由原版一起翻,变了的格都记进实际账。
 */
final class DoorOpener {

    private DoorOpener() {}

    /** 这扇门已经从规划时的样子翻过来了。 */
    static boolean opened(Edit.Door door, BlockState now) {
        return now.is(door.state().getBlock()) && now.hasProperty(BlockStateProperties.OPEN)
                && now.getValue(BlockStateProperties.OPEN) != door.state().getValue(BlockStateProperties.OPEN);
    }

    /** @param unseen 门看不见、准星还没落上时交给调用方计数 */
    static Beat tick(Rig rig, Edit.Door door, Function<Hitch, Beat> unseen) {
        BlockPos pos = door.pos();
        Vec3 point = rig.look.point(pos);
        if (point == null) {
            return unseen.apply(Hitch.OCCLUDED);
        }
        rig.look.at(point);
        if (rig.mouse.on(pos) == null) {
            return unseen.apply(Hitch.OCCLUDED);
        }
        boolean sneaking = rig.entity.isShiftKeyDown();
        rig.entity.setShiftKeyDown(false);
        Mouse.Use use = rig.use();
        rig.entity.setShiftKeyDown(sneaking);
        return switch (use) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes(), pos, null);
                if (PathLog.debugging()) {
                    PathLog.debug("{} 开关门 {}", rig.who, Work.changes(pressed));
                }
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> Work.refused(rig, "开关门", refused.pos(), refused.reason());
            case Mouse.Use.Pressed pressed -> Beat.IDLE;
            case Mouse.Use.Waiting waiting -> Beat.IDLE;
        };
    }
}
