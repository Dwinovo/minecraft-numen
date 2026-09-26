package com.dwinovo.numen.core.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * 一份施工图摆到哪儿、朝哪儿:原点 {@code (0,0,0)} 落在 {@code anchor},整份绕原点顺时针(俯视)转 {@code quarters} 个
 * 90°。设计与蓝图文件摆下去都经它——同一条变换,方块朝向跟着转。
 *
 * <p>为什么绕原点而不是"转完再把最小角对齐到锚点":设计改了会变大变小,最小角跟着挪;同一栋房子按同一个落点再摆一次,
 * 原来的墙必须落回原来的格子,差异才算得对。绕原点转,原点永远在锚点上。
 *
 * <p>位置与方向都由原版算({@link StructureTemplate#transform}、{@link BlockState#rotate}):楼梯朝向、门的合页转过去
 * 该是什么样,是原版自己的规则。
 */
public record Placement(BlockPos anchor, int quarters) {

    /** 原地不转:当场执行的原语写的就是世界坐标。 */
    public static final Placement IN_PLACE = new Placement(BlockPos.ZERO, 0);

    public Placement {
        anchor = anchor.immutable();
        quarters = Math.floorMod(quarters, 4);
    }

    /**
     * 把顺时针转的度数读成几个 90°。只认 0、90、180、270:别的度数转不出方块世界里的朝向,当场说清。
     *
     * @param degrees 没给是 null,就是不转
     */
    public static int quarters(Integer degrees) {
        if (degrees == null) {
            return 0;
        }
        return switch (degrees) {
            case 0 -> 0;
            case 90 -> 1;
            case 180 -> 2;
            case 270 -> 3;
            default -> throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270, got " + degrees);
        };
    }

    public Rotation rotation() {
        return switch (quarters) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** 转了多少度,{@code build built} 这样报。 */
    public int degrees() {
        return quarters * 90;
    }

    /** 一格落在世界哪儿。 */
    public BlockPos cell(BlockPos relative) {
        return StructureTemplate.transform(relative, Mirror.NONE, rotation(), BlockPos.ZERO).offset(anchor);
    }

    /** 一个点(摆设实体的位置)落在世界哪儿;方块的格子占 {@code [x, x+1)},转法与 {@link #cell} 一致。 */
    public Vec3 point(Vec3 relative) {
        return StructureTemplate.transform(relative, Mirror.NONE, rotation(), BlockPos.ZERO)
                .add(anchor.getX(), anchor.getY(), anchor.getZ());
    }

    /** 方块跟着转:楼梯、原木、门的朝向。 */
    public BlockState turn(BlockState state) {
        return state.rotate(rotation());
    }
}
