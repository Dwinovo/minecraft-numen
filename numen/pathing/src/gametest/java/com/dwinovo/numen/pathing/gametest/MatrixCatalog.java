package com.dwinovo.numen.pathing.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 矩阵用例的方块目录:一种"方块样子"由几个相对于主格的零件组成(门有上下两半,床有头有脚),朝向都按场景的标准方向(目的地在东边)写,
 * 场景旋转时零件的位置与方块状态一起转。分家族列,每个家族一条 GameTest。
 */
final class MatrixCatalog {

    private MatrixCatalog() {}

    /** 一个零件:相对主格的偏移与方块状态。 */
    record Part(int dx, int dy, int dz, BlockState state) {}

    /** 一种方块样子。{@code water} 是它自己带水(含水的半砖、流水),场景要多等几刻让水流稳定。 */
    record Variant(String family, String name, List<Part> parts, boolean water) {

        boolean fluid() {
            return water || parts.stream().anyMatch(p -> !p.state().getFluidState().isEmpty());
        }

        /** 方块状态里有随朝向变的属性:场景转个方向,这样的方块才跟着变;没有的转不转都一样,不必转着再走一遍。 */
        boolean oriented() {
            return parts.stream().anyMatch(part -> part.state().getProperties().stream().anyMatch(MatrixCatalog::turns));
        }
    }

    private static final java.util.Set<String> SIDES = java.util.Set.of("north", "east", "south", "west");

    /**
     * 一个属性是不是随朝向变:值是方向或轴(facing、axis),旋转角(rotation),楼梯与铁轨的拐角(shape),
     * 以及四向的连接(栅栏、墙、玻璃板、绊线的 north、east、south、west)。
     */
    private static boolean turns(net.minecraft.world.level.block.state.properties.Property<?> property) {
        Class<?> values = property.getValueClass();
        return values == Direction.class || values == Direction.Axis.class
                || property.getName().equals("rotation") || property.getName().equals("shape")
                || SIDES.contains(property.getName());
    }

    private static Variant one(String family, String name, BlockState state) {
        return new Variant(family, name, List.of(new Part(0, 0, 0, state)), false);
    }

    private static Variant one(String family, String name, Block block) {
        return one(family, name, block.defaultBlockState());
    }

    /** 对照:什么也不摆,地形原样。 */
    static final Variant NONE = new Variant("base", "none", List.of(), false);

    private static final Direction[] FOUR = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    private static String dir(Direction d) {
        return d.getName().substring(0, 1);
    }

    // ==================== 半砖与楼梯 ====================

    static List<Variant> slabs() {
        List<Variant> out = new ArrayList<>();
        out.add(one("slab", "slab_bottom", Scenes.slab(SlabType.BOTTOM)));
        out.add(one("slab", "slab_top", Scenes.slab(SlabType.TOP)));
        out.add(one("slab", "slab_double", Scenes.slab(SlabType.DOUBLE)));
        out.add(wet("slab", "slab_bottom_wet", Scenes.slab(SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true)));
        out.add(wet("slab", "slab_top_wet", Scenes.slab(SlabType.TOP).setValue(BlockStateProperties.WATERLOGGED, true)));
        return out;
    }

    static List<Variant> stairs() {
        List<Variant> out = new ArrayList<>();
        for (Half half : Half.values()) {
            for (Direction facing : FOUR) {
                out.add(one("stair", "stair_" + half.getSerializedName() + "_" + dir(facing), Scenes.stair(facing, half)));
            }
        }
        out.add(wet("stair", "stair_bottom_E_wet",
                Scenes.stair(Direction.EAST, Half.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true)));
        out.add(wet("stair", "stair_top_E_wet",
                Scenes.stair(Direction.EAST, Half.TOP).setValue(BlockStateProperties.WATERLOGGED, true)));
        return out;
    }

    // ==================== 门、活板门、栅栏、墙、栅栏门 ====================

    private static Variant door(String name, Block block, Direction facing, boolean open) {
        BlockState lower = block.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing)
                .setValue(net.minecraft.world.level.block.DoorBlock.OPEN, open)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, DoubleBlockHalf.LOWER);
        return new Variant("door", name, List.of(new Part(0, 0, 0, lower),
                new Part(0, 1, 0, lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF, DoubleBlockHalf.UPPER))), false);
    }

    static List<Variant> doors() {
        List<Variant> out = new ArrayList<>();
        for (boolean open : new boolean[] {false, true}) {
            for (Direction facing : FOUR) {
                out.add(door("oak_door_" + (open ? "open" : "shut") + "_" + dir(facing), Blocks.OAK_DOOR, facing, open));
            }
        }
        for (boolean open : new boolean[] {false, true}) {
            for (Direction facing : new Direction[] {Direction.EAST, Direction.SOUTH}) {
                out.add(door("iron_door_" + (open ? "open" : "shut") + "_" + dir(facing), Blocks.IRON_DOOR, facing, open));
            }
        }
        return out;
    }

    static List<Variant> trapdoors() {
        List<Variant> out = new ArrayList<>();
        for (Half half : Half.values()) {
            out.add(one("trapdoor", "oak_trapdoor_" + half.getSerializedName() + "_shut",
                    Scenes.trapdoor(Blocks.OAK_TRAPDOOR, Direction.EAST, half, false)));
            for (Direction facing : FOUR) {
                out.add(one("trapdoor", "oak_trapdoor_" + half.getSerializedName() + "_open_" + dir(facing),
                        Scenes.trapdoor(Blocks.OAK_TRAPDOOR, facing, half, true)));
            }
            out.add(one("trapdoor", "iron_trapdoor_" + half.getSerializedName() + "_shut",
                    Scenes.trapdoor(Blocks.IRON_TRAPDOOR, Direction.EAST, half, false)));
            out.add(one("trapdoor", "iron_trapdoor_" + half.getSerializedName() + "_open_E",
                    Scenes.trapdoor(Blocks.IRON_TRAPDOOR, Direction.EAST, half, true)));
        }
        out.add(wet("trapdoor", "oak_trapdoor_bottom_shut_wet",
                Scenes.trapdoor(Blocks.OAK_TRAPDOOR, Direction.EAST, Half.BOTTOM, false).setValue(BlockStateProperties.WATERLOGGED, true)));
        return out;
    }

    static List<Variant> fences() {
        List<Variant> out = new ArrayList<>();
        out.add(one("fence", "oak_fence", Blocks.OAK_FENCE));
        out.add(one("fence", "cobble_wall", Blocks.COBBLESTONE_WALL));
        out.add(one("fence", "iron_bars", Blocks.IRON_BARS));
        out.add(one("fence", "glass_pane", Blocks.GLASS_PANE));
        for (boolean open : new boolean[] {false, true}) {
            for (Direction facing : new Direction[] {Direction.EAST, Direction.SOUTH}) {
                out.add(one("fence", "fence_gate_" + (open ? "open" : "shut") + "_" + dir(facing),
                        Blocks.OAK_FENCE_GATE.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, facing)
                                .setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, open)));
            }
        }
        return out;
    }

    // ==================== 薄的、软的、滑的 ====================

    static List<Variant> thin() {
        List<Variant> out = new ArrayList<>();
        out.add(one("thin", "carpet", Blocks.WHITE_CARPET));
        for (int layers = 1; layers <= 8; layers++) {
            out.add(one("thin", "snow_" + layers,
                    Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers)));
        }
        out.add(one("thin", "short_grass", Blocks.SHORT_GRASS));
        out.add(one("thin", "poppy", Blocks.POPPY));
        out.add(one("thin", "pressure_plate_stone", Blocks.STONE_PRESSURE_PLATE));
        out.add(one("thin", "pressure_plate_oak", Blocks.OAK_PRESSURE_PLATE));
        out.add(one("thin", "tripwire", Blocks.TRIPWIRE));
        return out;
    }

    static List<Variant> floors() {
        List<Variant> out = new ArrayList<>();
        out.add(one("floor", "soul_sand", Blocks.SOUL_SAND));
        out.add(one("floor", "mud", Blocks.MUD));
        out.add(one("floor", "honey_block", Blocks.HONEY_BLOCK));
        out.add(one("floor", "slime_block", Blocks.SLIME_BLOCK));
        out.add(one("floor", "farmland", Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7)));
        out.add(one("floor", "dirt_path", Blocks.DIRT_PATH));
        out.add(one("floor", "ice", Blocks.ICE));
        out.add(one("floor", "hay_block", Blocks.HAY_BLOCK));
        out.add(one("floor", "glass", Blocks.GLASS));
        out.add(one("floor", "sand", Blocks.SAND));
        return out;
    }

    // ==================== 不足一格高、或不是整格的 ====================

    static List<Variant> low() {
        List<Variant> out = new ArrayList<>();
        out.add(new Variant("low", "bed", List.of(
                new Part(0, 0, 0, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
                        .setValue(net.minecraft.world.level.block.BedBlock.PART, BedPart.FOOT)),
                new Part(1, 0, 0, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
                        .setValue(net.minecraft.world.level.block.BedBlock.PART, BedPart.HEAD))), false));
        out.add(one("low", "chest", Blocks.CHEST));
        out.add(one("low", "enchanting_table", Blocks.ENCHANTING_TABLE));
        out.add(one("low", "cauldron", Blocks.CAULDRON));
        out.add(one("low", "hopper", Blocks.HOPPER));
        out.add(one("low", "anvil", Blocks.ANVIL));
        out.add(one("low", "cake", Blocks.CAKE));
        out.add(one("low", "flower_pot", Blocks.FLOWER_POT));
        out.add(one("low", "lantern", Blocks.LANTERN));
        out.add(one("low", "brewing_stand", Blocks.BREWING_STAND));
        out.add(one("low", "stonecutter", Blocks.STONECUTTER));
        out.add(one("low", "daylight_detector", Blocks.DAYLIGHT_DETECTOR));
        out.add(one("low", "end_portal_frame", Blocks.END_PORTAL_FRAME));
        out.add(one("low", "composter", Blocks.COMPOSTER));
        out.add(one("low", "candle", Blocks.CANDLE));
        out.add(one("low", "skeleton_skull", Blocks.SKELETON_SKULL));
        out.add(one("low", "sea_pickle", Blocks.SEA_PICKLE.defaultBlockState().setValue(net.minecraft.world.level.block.SeaPickleBlock.WATERLOGGED, false)));
        return out;
    }

    static List<Variant> hazards() {
        List<Variant> out = new ArrayList<>();
        out.add(one("hazard", "cactus", Blocks.CACTUS));
        out.add(one("hazard", "sweet_berry_bush", Blocks.SWEET_BERRY_BUSH));
        out.add(one("hazard", "magma_block", Blocks.MAGMA_BLOCK));
        out.add(one("hazard", "campfire", Blocks.CAMPFIRE));
        out.add(one("hazard", "cobweb", Blocks.COBWEB));
        out.add(one("hazard", "powder_snow", Blocks.POWDER_SNOW));
        out.add(one("hazard", "turtle_egg", Blocks.TURTLE_EGG));
        return out;
    }

    // ==================== 能攀的 ====================

    static List<Variant> climbables() {
        List<Variant> out = new ArrayList<>();
        // 梯子贴着东边的墙(场景在 COLUMN 里立墙),朝西
        out.add(one("climb", "ladder", Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.WEST)));
        out.add(one("climb", "vine", Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.EAST, true)));
        out.add(one("climb", "scaffolding", Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM, false)));
        out.add(one("climb", "twisting_vines", Blocks.TWISTING_VINES_PLANT));
        out.add(one("climb", "chain", Blocks.CHAIN));
        return out;
    }

    // ==================== 水 ====================

    static List<Variant> fluids() {
        List<Variant> out = new ArrayList<>();
        out.add(wet("fluid", "water_source", Blocks.WATER.defaultBlockState()));
        out.add(wet("fluid", "lily_pad", Blocks.LILY_PAD.defaultBlockState()));
        return out;
    }

    private static Variant wet(String family, String name, BlockState state) {
        return new Variant(family, name, List.of(new Part(0, 0, 0, state)), true);
    }
}
