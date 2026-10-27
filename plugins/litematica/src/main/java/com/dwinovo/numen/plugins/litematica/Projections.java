package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 读主人的 Litematica 里加载着的投影:有哪些、各占哪儿、每一格是什么。整个联动里读投影的唯一一处;碰 Litematica 的类的两处之一
 * (另一处是 {@link Preview},它写)。必须在客户端线程上调:Litematica 的投影表不是线程安全的,而客户端函数本来就在那里执行。
 *
 * <h2>格怎么取</h2>
 * Litematica 没有"某个投影的每一格在世界的哪儿、是什么"的接口,它把投影放进世界、渲染都是一块一块按区块做的
 * ({@code SchematicPlacingUtils.placeBlocksWithinChunk})。这里照它的算法整块算一遍,位置变换全用它自己的
 * {@link PositionUtils},不另写旋转镜像:投影原点加子区域位置、投影的镜像旋转、子区域自己的镜像旋转,再对方块状态做同样的镜像旋转。
 * 差别只有范围(整个子区域而不是一个区块的那一截)和去处(收下,不放进世界)。
 */
final class Projections {

    private Projections() {}

    static LitematicaApi.Loaded loaded() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        List<LitematicaApi.Placement> placements = new ArrayList<>();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            placements.add(describe(placement, placement == selected));
        }
        return new LitematicaApi.Loaded(layer(DataManager.getRenderLayerRange()), placements);
    }

    static LitematicaApi.Placement describe(SchematicPlacement placement, boolean selected) {
        int[] count = {0};
        forEachCell(placement, (pos, state) -> count[0]++);
        return new LitematicaApi.Placement(placement.getName(),
                Optional.ofNullable(placement.getSchematicFile()).map(java.io.File::getName),
                placement.getOrigin().immutable(),
                bounds(placement),
                degrees(placement.getRotation()),
                placement.getMirror(),
                placement.isEnabled(),
                selected,
                count[0]);
    }

    /** 投影占的世界盒:开着的子区域的外接盒;没有开着的子区域就没有。 */
    private static Optional<LitematicaApi.Bounds> bounds(SchematicPlacement placement) {
        List<Box> boxes = new ArrayList<>();
        for (Map.Entry<String, Box> entry : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).entrySet()) {
            if (placement.getRelativeSubRegionPlacement(entry.getKey()).isEnabled()) {
                boxes.add(entry.getValue());
            }
        }
        var corners = PositionUtils.getEnclosingAreaCorners(boxes);
        if (corners == null) {
            return Optional.empty();
        }
        BlockPos min = corners.getLeft().immutable();
        BlockPos max = corners.getRight().immutable();
        return Optional.of(new LitematicaApi.Bounds(min, max,
                new LitematicaApi.Size(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1)));
    }

    private static int degrees(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
        };
    }

    private static LitematicaApi.Layer layer(LayerRange range) {
        LayerMode mode = range.getLayerMode();
        boolean bounded = mode != LayerMode.ALL;
        // ALL 没有范围;ALL_ABOVE 没有上界,ALL_BELOW 没有下界(Litematica 用 ±30000000 当没有)
        Optional<Integer> from = bounded && mode != LayerMode.ALL_BELOW ? Optional.of(range.getLayerMin()) : Optional.empty();
        Optional<Integer> to = bounded && mode != LayerMode.ALL_ABOVE ? Optional.of(range.getLayerMax()) : Optional.empty();
        return new LitematicaApi.Layer(mode.getStringValue(), range.getAxis(), from, to);
    }

    /**
     * 一个投影在世界 y 从 {@code from} 到 {@code to}(含)的每一层的格,带完整方块状态,写成 {@code /setblock} 的写法。
     *
     * @param named 点名的投影,没点名就是主人选中的那份
     * @throws ApiError 点名的投影不在、没点名又没有选中的;或要的格超过一次交回的上限
     */
    static List<BlockAt> cells(int from, int to, String named) {
        SchematicPlacement placement = find(named);
        int asked = Math.min(from, to);
        int askedTo = Math.max(from, to);
        // 投影够不到的层没有格;按它占的高度收窄,免得要一个很大的范围就分配一张很大的表
        Optional<LitematicaApi.Bounds> bounds = bounds(placement);
        if (bounds.isEmpty()) {
            return List.of();
        }
        int lowest = Math.max(asked, bounds.get().min().getY());
        int highest = Math.min(askedTo, bounds.get().max().getY());
        if (lowest > highest) {
            return List.of();
        }
        int[] perLayer = new int[highest - lowest + 1];
        int[] total = {0};
        forEachCell(placement, (pos, state) -> {
            if (pos.getY() >= lowest && pos.getY() <= highest) {
                perLayer[pos.getY() - lowest]++;
                total[0]++;
            }
        });
        if (total[0] > LuaCodecs.MAX_CELLS) {
            throw tooMany(asked, askedTo, lowest, perLayer, total[0], named);
        }
        List<BlockAt> cells = new ArrayList<>(total[0]);
        forEachCell(placement, (pos, state) -> {
            if (pos.getY() >= lowest && pos.getY() <= highest) {
                cells.add(new BlockAt(BlockStateParser.serialize(state), pos.immutable()));
            }
        });
        return cells;
    }

    /** @param lowest {@code perLayer} 的第一层,即要的范围里投影够得到的最低一层 */
    private static ApiError tooMany(int asked, int askedTo, int lowest, int[] perLayer, int total, String named) {
        int fits = Layers.fitting(perLayer, LuaCodecs.MAX_CELLS);
        String message = "Layers " + asked + " to " + askedTo + " hold " + total + " cells, over the " + LuaCodecs.MAX_CELLS
                + " one call returns.";
        if (fits == 0) {
            return new ApiError(ErrorKind.BAD_ARGUMENT, message + " Layer " + lowest + " alone holds " + perLayer[0]
                    + ", more than one call can return.", null, Map.of("cells", (long) total, "limit", (long) LuaCodecs.MAX_CELLS));
        }
        int last = lowest + fits - 1;
        // 点名了投影才写进提示:没点名时那一行读的就是选中的那份,和这次一样
        Object[] args = named == null ? new Object[]{lowest, last} : new Object[]{lowest, last, Map.of("placement", named)};
        return new ApiError(ErrorKind.BAD_ARGUMENT, message + " Layers " + lowest + " to " + last + " fit.",
                Call.of("litematica.placement.cells", args),
                Map.of("cells", (long) total, "limit", (long) LuaCodecs.MAX_CELLS, "fits_to", (long) last));
    }

    /** 点名的那份,没点名就是主人选中的。 */
    static SchematicPlacement find(String named) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (named == null) {
            SchematicPlacement selected = manager.getSelectedSchematicPlacement();
            if (selected == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "Your owner has no projection selected in Litematica.",
                        "print(litematica.placement.list())");
            }
            return selected;
        }
        List<SchematicPlacement> matches = manager.getAllSchematicsPlacements().stream()
                .filter(p -> named.equals(p.getName())).toList();
        if (matches.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "Your owner has no projection called " + named + " loaded.",
                    "print(litematica.placement.list())");
        }
        if (matches.size() > 1) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, matches.size() + " projections are called " + named
                    + ", so the name does not say which. Ask your owner to rename one in Litematica, or select the one "
                    + "you want and leave the name out.", null);
        }
        return matches.get(0);
    }

    /**
     * 一个投影里每个有方块的格:开着的子区域,不含空气与结构空位(Litematica 放置时同样跳过结构空位)。回调拿到的是世界坐标
     * (同一个可变对象,要留就拷贝)与放进世界时的方块状态。
     */
    private static void forEachCell(SchematicPlacement placement, BiConsumer<BlockPos.MutableBlockPos, BlockState> visitor) {
        LitematicaSchematic schematic = placement.getSchematic();
        Rotation turned = placement.getRotation();
        Mirror mirrored = placement.getMirror();
        for (SubRegionPlacement sub : placement.getAllSubRegionsPlacements()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(sub.getName());
            var regionSize = schematic.getAreaSizeAsVec3i(sub.getName());
            if (!sub.isEnabled() || container == null || regionSize == null) {
                continue;
            }
            BlockPos regionPos = sub.getPos();
            // 子区域里的下标 (0,0,0) 在它最低的那个角,而子区域的位置是它画的那个角(可以是任何一个)
            BlockPos posEndRel = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(regionSize)).offset(regionPos);
            BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);
            BlockPos total = PositionUtils.getTransformedBlockPos(regionPos, mirrored, turned).offset(placement.getOrigin());
            Rotation rotation = turned.getRotated(sub.getRotation());
            Mirror subMirror = sub.getMirror();
            if (subMirror != Mirror.NONE && (turned == Rotation.CLOCKWISE_90 || turned == Rotation.COUNTERCLOCKWISE_90)) {
                subMirror = subMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
            }
            int offsetX = posMinRel.getX() - regionPos.getX();
            int offsetY = posMinRel.getY() - regionPos.getY();
            int offsetZ = posMinRel.getZ() - regionPos.getZ();
            BlockPos.MutableBlockPos within = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos world = new BlockPos.MutableBlockPos();
            for (int y = 0; y < Math.abs(regionSize.getY()); y++) {
                for (int z = 0; z < Math.abs(regionSize.getZ()); z++) {
                    for (int x = 0; x < Math.abs(regionSize.getX()); x++) {
                        BlockState state = container.get(x, y, z);
                        if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
                            continue;
                        }
                        within.set(offsetX + x, offsetY + y, offsetZ + z);
                        world.set(PositionUtils.getTransformedPlacementPosition(within, placement, sub).offset(total));
                        if (mirrored != Mirror.NONE) {
                            state = state.mirror(mirrored);
                        }
                        if (subMirror != Mirror.NONE) {
                            state = state.mirror(subMirror);
                        }
                        if (rotation != Rotation.NONE) {
                            state = state.rotate(rotation);
                        }
                        visitor.accept(world, state);
                    }
                }
            }
        }
    }
}
