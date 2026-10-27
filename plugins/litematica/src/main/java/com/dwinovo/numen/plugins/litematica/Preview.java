package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.FileType;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把她的设计做成主人客户端里的一份 Litematica 投影:格子变成一份 {@link LitematicaSchematic},存成蓝图目录下 {@code numen/<名字>.litematic}
 * (主人以后还能自己加载、改、分享),再从那个文件加载出来作为 placement 摆在格子本来的世界坐标上。只改主人的客户端,不动世界。
 *
 * <p>蓝图由 Litematica 自己的类创建与写盘({@link LitematicaSchematic#createEmptySchematic}、{@code writeToFile}),不自己拼 NBT。
 * 摆出来的 placement 读的是刚写下的文件,所以主人看到的就是文件里的内容,不是内存里另一份。同名的再调一次:同一个文件的旧投影先移走、
 * 文件覆盖、重新加载,幂等。
 */
final class Preview {

    /** 蓝图目录下放她的投影的子目录。 */
    private static final String FOLDER = "numen";

    /** 名字就是文件名:不许路径分隔符、冒号这类各系统文件名里不合法的字符。 */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _.()-]+");

    private Preview() {}

    static LitematicaApi.Placement show(List<BlockAt> cells, String name) {
        if (!NAME.matcher(name).matches() || name.startsWith(".") || !name.equals(name.strip())) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "The name " + name + " is also the file name, so it can hold letters, "
                    + "digits, spaces and _ - . ( ) only, and not start with a dot or a space.", null);
        }
        // 同一格写两次留后一笔,和 numen.build 收一串格时一样;空气是"这一格挖空",投影里没有它
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            BlockPos pos = cells.get(i).pos().immutable();
            BlockState state = parse(cells.get(i).name(), i + 1);
            blocks.remove(pos);
            if (!state.isAir()) {
                blocks.put(pos, state);
            }
        }
        if (blocks.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "There is nothing to show: every cell is air, or there are no cells.", null);
        }

        BlockPos min = blocks.keySet().iterator().next();
        BlockPos max = min;
        for (BlockPos pos : blocks.keySet()) {
            min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
            max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
        }

        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(min, max, name), true);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "Numen");
        if (schematic == null) {
            throw new ApiError(ErrorKind.FAILED, "Litematica could not create a schematic for these cells.", null);
        }
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            container.set(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ(), entry.getValue());
        }
        long now = System.currentTimeMillis();
        schematic.getMetadata().setTotalBlocks(blocks.size());
        schematic.getMetadata().setTimeCreated(now);
        schematic.getMetadata().setTimeModified(now);

        File dir = new File(DataManager.getSchematicsBaseDirectory(), FOLDER);
        File file = LitematicaSchematic.fileFromDirAndName(dir, name, FileType.LITEMATICA_SCHEMATIC);
        removeLoaded(file);
        if (!schematic.writeToFile(dir, name, true)) {
            throw new ApiError(ErrorKind.FAILED, "Litematica could not write " + file.getName() + " to " + dir
                    + " (its message is on your owner's screen).", null);
        }
        LitematicaSchematic saved = SchematicHolder.getInstance().getOrLoad(file);
        if (saved == null) {
            throw new ApiError(ErrorKind.FAILED, "Litematica could not read back " + file + " that it just wrote.", null);
        }
        SchematicPlacement placement = SchematicPlacement.createFor(saved, min, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, true);
        return Projections.describe(placement, placement == DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement());
    }

    /** 这个文件已经加载着的投影和蓝图:同一个文件就是同一份投影,新的来了旧的先走。 */
    private static void removeLoaded(File file) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : new ArrayList<>(manager.getAllSchematicsPlacements())) {
            if (file.equals(placement.getSchematicFile())) {
                manager.removeSchematicPlacement(placement);
            }
        }
        for (LitematicaSchematic loaded : new ArrayList<>(SchematicHolder.getInstance().getAllSchematics())) {
            if (file.equals(loaded.getFile())) {
                SchematicHolder.getInstance().removeSchematic(loaded);
            }
        }
    }

    /**
     * 一格要放的方块,照 {@code /setblock} 的写法读;读不通的原版解析器的话原样转述。Numen API 没有"文本读成方块状态"的东西,
     * 原版的 {@link BlockStateParser} 就是这件事的唯一真源:参数与 Numen 建造读同一种写法时一样(按方块注册表查、不带方块实体数据、
     * 先去掉首尾空白),所以建造收得下的写法这里都读得通,交回的也一样。
     */
    private static BlockState parse(String written, int index) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written.trim(), false).blockState();
        } catch (CommandSyntaxException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "Cell " + index + ": " + written + " — " + bad.getMessage(), null);
        }
    }
}
