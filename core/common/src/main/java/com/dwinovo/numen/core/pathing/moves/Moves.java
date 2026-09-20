package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.moves.movements.MovementAscend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDescend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDiagonal;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDownward;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;
import com.dwinovo.numen.core.pathing.moves.movements.MovementParkour;
import com.dwinovo.numen.core.pathing.moves.movements.MovementPillar;
import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 全部移动原语 × 全部方向的枚举:搜索循环遍历它产出邻边。
 * 静态偏移的成员由 {@link #apply} 直接填结果;dynamicXZ/dynamicY
 * 的成员(下降可变坠落、对角可变高差、跑酷可变距离)覆写 apply
 * 由成本函数写入实际落点。
 */
public enum Moves {

    DOWNWARD(0, -1, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementDownward(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementDownward.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementPillar(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementPillar.cost(context, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementTraverse(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementTraverse(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementTraverse(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementTraverse(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementAscend(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementAscend(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementAscend(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementAscend(context.player, context.spec, src, dest);
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            // 恰低一格是下降,更低是坠落——照搜索给的落点分派
            return dest.getY() == src.getY() - 1
                    ? new MovementDescend(context.player, context.spec, src, dest)
                    : new MovementFall(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            // 恰低一格是下降,更低是坠落——照搜索给的落点分派
            return dest.getY() == src.getY() - 1
                    ? new MovementDescend(context.player, context.spec, src, dest)
                    : new MovementFall(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            // 恰低一格是下降,更低是坠落——照搜索给的落点分派
            return dest.getY() == src.getY() - 1
                    ? new MovementDescend(context.player, context.spec, src, dest)
                    : new MovementFall(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            // 恰低一格是下降,更低是坠落——照搜索给的落点分派
            return dest.getY() == src.getY() - 1
                    ? new MovementDescend(context.player, context.spec, src, dest)
                    : new MovementFall(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementDiagonal(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementDiagonal(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementDiagonal(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementDiagonal(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementParkour(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementParkour(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementParkour(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement build(CalculationContext context, BlockPos src, BlockPos dest) {
            return new MovementParkour(context.player, context.spec, src, dest);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.WEST, result);
        }
    };

    /** 落点是否随地形变化(跑酷距离可变)。 */
    public final boolean dynamicXZ;
    /** 落点高度是否随地形变化(下降可能变坠落、对角可 ±1)。 */
    public final boolean dynamicY;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    /**
     * 按搜索定下的这条边构造可执行的移动实例(路径装配期用)。
     *
     * <p><b>落点由调用方给,不在这里重算。</b>搜索展开这条边时已经由 {@link #apply} 算出过
     * 落点与价钱,那就是这条边的唯一出处;装配时再算一遍等于让同一件事有两处说了算——
     * 世界在搜索途中变了,两处就会得出不同的落点,而执行的和定价的不再是同一个动作。
     */
    public abstract Movement build(CalculationContext context, BlockPos src, BlockPos dest);

    /**
     * 计算从 (x,y,z) 走本方向的落点与成本。静态成员直接按偏移填
     * 结果;动态成员覆写并由成本函数写实际落点。
     */
    public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("动态偏移的移动必须覆写 apply");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(CalculationContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("移动必须覆写 cost 或 apply");
    }
}
