package com.dwinovo.numen.pathing.gametest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.gametest.MatrixCatalog.Part;
import com.dwinovo.numen.pathing.gametest.MatrixCatalog.Variant;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.MoveKind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矩阵用例的一个场景:一块 9×9 的地,身体站在 S(局部坐标 (3,3)),目的地在它东边;地形是 {@link Base},要试的方块样子({@link Variant})摆在
 * {@link Where} 指的那几列里的 {@link Level} 那一层,整个场景可以转 {@link #rot} 个四分之一圈(方块的朝向随着转)。
 * 场景只描述怎么搭、问什么;搭与问由 {@link Matrix} 做。
 */
record MatrixScene(Base base, Where where, Level level, Variant variant, int rot) {

    /** 场地边长。 */
    static final int SIZE = 9;
    /** 身体站的那一列(局部坐标):在场地正中,转到哪个方向地形都落在场地里(往前最远四列、往后两列、两侧各两列)。 */
    static final int SX = 4;
    static final int SZ = 4;
    /** 平地的地面最上面一层实心方块的高度:脚站在 {@code F + 1}。 */
    static final int F = 6;
    /** 场地清空到多高。 */
    static final int ROOF = F + 8;

    /** 身体的列、目的地的列用的相对偏移 {@code (dx, dz)},标准方向下东是 +dx、南是 +dz。 */
    record Col(int dx, int dz) {}

    /** 一列的地形:实心到第 {@code solid} 层(-1 没有),水从实心之上填到第 {@code water} 层(不比 solid 高就没有水)。 */
    record Terrain(int solid, int water) {

        int surface() {
            return Math.max(solid, water);
        }
    }

    /** 地形。 */
    enum Base {
        /** 平地,目的地在东一格。 */
        FLAT(EnumSet.of(MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL), List.of(new Heading(1, 0, 0)), F + 1),
        /** 平地,目的地在东南斜对角,东边与南边那两列是斜着过时擦着的。 */
        DIAG(EnumSet.of(MoveKind.DIAGONAL), List.of(new Heading(1, 0, 1)), F + 1),
        /** 东边整片高一层。 */
        UP(EnumSet.of(MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL), List.of(new Heading(1, 0, 0)), F + 1),
        /** 东边整片低一层。 */
        DOWN(EnumSet.of(MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL), List.of(new Heading(1, 0, 0)), F + 1),
        /** 东边整片低三层。 */
        DROP(EnumSet.of(MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL), List.of(new Heading(1, 0, 0)), F + 1),
        /** 东边隔一列空,再往东是地面。 */
        GAP(EnumSet.of(MoveKind.WALK, MoveKind.DESCEND, MoveKind.FALL, MoveKind.PARKOUR), List.of(new Heading(1, 0, 0)), F + 1),
        /** 东边隔两列空。 */
        GAP2(EnumSet.of(MoveKind.WALK, MoveKind.DESCEND, MoveKind.FALL, MoveKind.PARKOUR), List.of(new Heading(1, 0, 0)), F + 1),
        /** 整片是水,身体浮在水面。 */
        POOL(EnumSet.of(MoveKind.SWIM, MoveKind.WALK, MoveKind.ASCEND), List.of(new Heading(1, 0, 0)), F),
        /** 身体在水里,东边是和水面齐平的岸。 */
        SHORE(EnumSet.of(MoveKind.SWIM, MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND), List.of(new Heading(1, 0, 0)), F),
        /** 身体在水里,东边的岸比水面高一格。 */
        SHORE_HIGH(EnumSet.of(MoveKind.SWIM, MoveKind.WALK, MoveKind.ASCEND, MoveKind.DESCEND), List.of(new Heading(1, 0, 0)), F),
        /** 身体在岸上,东边是水。 */
        ENTER(EnumSet.of(MoveKind.WALK, MoveKind.DESCEND, MoveKind.FALL, MoveKind.SWIM), List.of(new Heading(1, 0, 0)), F + 1),
        /** 身体在水岸上,东边是浅一格的水(涉水)。 */
        WADE(EnumSet.of(MoveKind.WALK, MoveKind.DESCEND, MoveKind.ASCEND), List.of(new Heading(1, 0, 0)), F + 1),
        /** 攀爬:身体站在要攀的那一列里,东边是墙。 */
        COLUMN(EnumSet.of(MoveKind.CLIMB), List.of(), F + 1),
        /** 平地,问垫柱(往上垫一块,需要规格许放、身上有料)。 */
        RISE(EnumSet.of(MoveKind.PILLAR), List.of(), F + 1),
        /** 平地,问向下挖(需要规格许挖、身上有镐)。 */
        SINK(EnumSet.of(MoveKind.DOWNWARD), List.of(), F + 1);

        final Set<MoveKind> moves;
        /** 标准方向下水平的问法。 */
        final List<Heading> headings;
        /** 找起点时从这一层起往上下找站得住的节点。 */
        final int startY;

        Base(Set<MoveKind> moves, List<Heading> headings, int startY) {
            this.moves = moves;
            this.headings = headings;
            this.startY = startY;
        }

        /** 标准方向下 {@code (dx, dz)} 这一列的地形;场地外为 null。 */
        Terrain terrain(int dx, int dz) {
            if (dx < -2 || dx > 4 || dz < -2 || dz > 2) {
                return null;
            }
            return switch (this) {
                case FLAT, DIAG, RISE, SINK -> new Terrain(F, F);
                case UP -> new Terrain(dx >= 1 ? F + 1 : F, dx >= 1 ? F + 1 : F);
                case DOWN -> new Terrain(dx >= 1 ? F - 1 : F, dx >= 1 ? F - 1 : F);
                case DROP -> new Terrain(dx >= 1 ? F - 3 : F, dx >= 1 ? F - 3 : F);
                case GAP -> dx == 1 ? new Terrain(-1, -1) : new Terrain(F, F);
                case GAP2 -> dx == 1 || dx == 2 ? new Terrain(-1, -1) : new Terrain(F, F);
                case POOL -> new Terrain(F - 3, F);
                case SHORE -> dx <= 0 ? new Terrain(F - 3, F) : new Terrain(F, F);
                case SHORE_HIGH -> dx <= 0 ? new Terrain(F - 3, F) : new Terrain(F + 1, F + 1);
                case ENTER -> dx <= 0 ? new Terrain(F, F) : new Terrain(F - 3, F);
                case WADE -> dx <= 0 ? new Terrain(F, F) : new Terrain(F - 1, F);
                case COLUMN -> dx == 1 ? new Terrain(F + 6, F + 6) : new Terrain(F, F);
            };
        }

        /** 这块地里有水(搭完要等水流稳定)。 */
        boolean wet() {
            return this == POOL || this == SHORE || this == SHORE_HIGH || this == ENTER || this == WADE;
        }

        /** 目的地那几列(标准方向)。 */
        List<Col> dest() {
            return switch (this) {
                case DIAG -> List.of(new Col(1, 1));
                case GAP -> List.of(new Col(2, 0));
                case GAP2 -> List.of(new Col(3, 0));
                case COLUMN -> List.of(new Col(0, 0));
                default -> List.of(new Col(1, 0));
            };
        }

        /** 斜走时擦着的两列、其余走法没有。 */
        List<Col> sides() {
            return this == DIAG ? List.of(new Col(1, 0), new Col(0, 1)) : List.of();
        }
    }

    /** 方块样子摆在哪几列。 */
    enum Where {
        /** 场地里的每一列(含身体站的那列)。 */
        ALL,
        /** 只在身体站的那一列。 */
        START,
        /** 只在目的地那几列。 */
        DEST,
        /** 只在斜走擦着的那两列。 */
        SIDES
    }

    /** 摆在那一列的哪一层。 */
    enum Level {
        /** 顶替那一列最上面的地面(或水面那一格)。 */
        FLOOR(0),
        /** 摆在地面上,身体脚下那一层。 */
        FEET(1),
        /** 摆在身体头那一层。 */
        HEAD(2),
        /** 摆在头顶再上一层:站直了够不着,垫柱升起来或起跳时头顶会撞上。 */
        CEILING(3);

        final int above;

        Level(int above) {
            this.above = above;
        }
    }

    /** 场景的名字,失败时指认用。 */
    String key() {
        return base + "/" + where + "/" + level + "/" + variant.name() + "/r" + rot;
    }

    /** 这个场景有水,搭完要等水流稳定。 */
    boolean wet() {
        return base.wet() || variant.fluid();
    }

    // ==================== 坐标 ====================

    static Rotation rotation(int rot) {
        return Rotation.values()[Math.floorMod(rot, 4)];
    }

    /** 标准方向下的偏移 {@code (dx, dz)} 转过 {@code rot} 个四分之一圈之后相对 S 的偏移。 */
    static BlockPos turn(int dx, int dy, int dz, int rot) {
        return new BlockPos(dx, dy, dz).rotate(rotation(rot));
    }

    /** 标准方向的问法转过之后的问法。 */
    Heading turned(Heading h) {
        BlockPos p = turn(h.dx(), h.dy(), h.dz(), rot);
        return new Heading(p.getX(), p.getY(), p.getZ());
    }

    /** 局部坐标 {@code (dx, y, dz)}(标准方向)在场景里的位置;{@code origin} 是场地的西北角底面。 */
    BlockPos at(BlockPos origin, int dx, int y, int dz) {
        BlockPos p = turn(dx, 0, dz, rot);
        return origin.offset(SX + p.getX(), y, SZ + p.getZ());
    }

    // ==================== 搭 ====================

    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * 在 {@code origin} 处搭出这个场景:先清空,再铺地形,再摆方块样子,摆完让它们按邻格再算一遍形状。方块样子在这个位置立不住
     * (地毯、雪悬在半空,沙子悬着会掉)的场景没有意义,返回 false。
     */
    boolean build(ServerLevel world, BlockPos origin) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y <= ROOF; y++) {
                for (int z = 0; z < SIZE; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
                    }
                }
            }
        }
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int dx = -SX; dx < SIZE - SX; dx++) {
            for (int dz = -SZ; dz < SIZE - SZ; dz++) {
                Terrain t = base.terrain(dx, dz);
                if (t == null) {
                    continue;
                }
                for (int y = 0; y <= t.solid(); y++) {
                    world.setBlock(at(origin, dx, y, dz), stone, QUIET);
                }
                for (int y = t.solid() + 1; y <= t.water(); y++) {
                    world.setBlock(at(origin, dx, y, dz), water, QUIET);
                }
            }
        }
        if (base.wet()) {
            retain(world, origin);
        }
        List<BlockPos> placed = new ArrayList<>();
        for (Col col : columns()) {
            Terrain t = base.terrain(col.dx(), col.dz());
            if (t == null || t.solid() < 0 && t.water() < 0) {
                continue;
            }
            if (base == Base.COLUMN) {
                // 攀爬的列:东边是墙(dx = 1 一路铺到 F + 6),攀爬方块摆在身体站的那一列的 F + 1 .. F + 4
                for (int y = F + 1; y <= F + 4; y++) {
                    put(world, origin, col, y, placed);
                }
                continue;
            }
            put(world, origin, col, t.surface() + level.above, placed);
        }
        boolean stands = true;
        for (BlockPos p : placed) {
            BlockState state = world.getBlockState(p);
            BlockState shaped = Block.updateFromNeighbourShapes(state, world, p);
            if (shaped != state) {
                world.setBlock(p, shaped, QUIET);
            }
            stands &= !shaped.isAir() && !state.isAir();
        }
        return stands;
    }

    /** 水池四周挡一圈石头,水不流出去。 */
    private void retain(ServerLevel world, BlockPos origin) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int dx = -SX; dx < SIZE - SX; dx++) {
            for (int dz = -SZ; dz < SIZE - SZ; dz++) {
                if (base.terrain(dx, dz) != null) {
                    continue;
                }
                for (int y = 0; y <= F + 2; y++) {
                    world.setBlock(at(origin, dx, y, dz), stone, QUIET);
                }
            }
        }
    }

    /** 摆方块样子的那几列。 */
    List<Col> columns() {
        List<Col> out = new ArrayList<>();
        switch (where) {
            case START -> out.add(new Col(0, 0));
            case DEST -> out.addAll(base.dest());
            case SIDES -> out.addAll(base.sides());
            case ALL -> {
                for (int dx = -2; dx <= 4; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        out.add(new Col(dx, dz));
                    }
                }
            }
        }
        return out;
    }

    private void put(ServerLevel world, BlockPos origin, Col col, int y, List<BlockPos> placed) {
        for (Part part : variant.parts()) {
            BlockPos extra = turn(part.dx(), 0, part.dz(), rot);
            BlockPos at = at(origin, col.dx(), y + part.dy(), col.dz()).offset(extra.getX(), 0, extra.getZ());
            world.setBlock(at, part.state().rotate(rotation(rot)), Block.UPDATE_ALL);
            placed.add(at);
        }
    }
}
