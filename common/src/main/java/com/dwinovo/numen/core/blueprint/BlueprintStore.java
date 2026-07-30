package com.dwinovo.numen.core.blueprint;

import com.dwinovo.numen.core.task.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 蓝图仓库:{@code schematics/} 目录下的结构文件。
 *
 * <p>目录名取社区通用的那个:玩家下载来的图纸本来就躺在那儿,不必再为我们
 * 单独搬一次家。旧的 {@code config/numen/blueprints} 继续兜底读取,已经放
 * 进去的图纸不会失踪。
 *
 * <p>支持两种载体,同一数据形态(原版结构 NBT:size + palette/palettes + blocks):
 * <ul>
 *   <li>{@code .nbt}——结构方块导出的压缩二进制,游戏内即可制作,网络资源海量;</li>
 *   <li>{@code .snbt}——同一数据的打包文本形态(gametest 用的那种),可读可 diff。</li>
 * </ul>
 *
 * <p>加载即展开为 {@link BuildTaskRecord.Target} 全量格集:调色板态原样入格
 * (朝向、轴向、半砖上下……由 setBlock 精确落位),显式空气格是"清空"指令,
 * 稀疏缺省格不触碰;门、床这类双格方块在模板里本就两半俱全,逐格写入天然成对。
 * 方块实体数据(箱子内容等)暂不携带。
 */
public final class BlueprintStore {

    private BlueprintStore() {}

    /** 单张蓝图的格数上限(防误载巨图把任务撑爆)。 */
    private static final int MAX_CELLS = 32768;

    /** 展开结果:目标格集 + 旋转后的占地尺寸 + 方块实体数据(按位置索引)。 */
    public record Loaded(List<BuildTaskRecord.Target> targets, Vec3i size,
                         java.util.Map<Long, CompoundTag> blockEntityData) {}

    /** 支持的图纸扩展名。 */
    private static final List<String> EXTENSIONS = List.of(".nbt", ".snbt", ".litematic", ".schem");

    /** 主目录:社区通用的 {@code schematics/}(不存在则建,新图纸也写这里)。 */
    public static Path dir(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create blueprint directory " + dir, e);
        }
        return dir;
    }

    /** 查找顺序:主目录优先,旧目录兜底(只读,不自动创建)。 */
    private static List<Path> searchRoots(MinecraftServer server) {
        List<Path> roots = new ArrayList<>(2);
        roots.add(dir(server));
        Path legacy = server.getServerDirectory().resolve("config").resolve("numen").resolve("blueprints");
        if (Files.isDirectory(legacy)) {
            roots.add(legacy);
        }
        return roots;
    }

    /** 列出可用蓝图名(不含扩展名,排序稳定;同名以主目录为准)。 */
    public static List<String> list(MinecraftServer server) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Path root : searchRoots(server)) {
            try (var stream = Files.list(root)) {
                stream.forEach(path -> {
                    String file = path.getFileName().toString();
                    String lower = file.toLowerCase(Locale.ROOT);
                    for (String ext : EXTENSIONS) {
                        if (lower.endsWith(ext)) {
                            names.add(file.substring(0, file.lastIndexOf('.')));
                            return;
                        }
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("cannot list blueprint directory " + root, e);
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /** 只读尺寸(列表工具的概要用)。 */
    public static Vec3i peekSize(MinecraftServer server, String name) {
        CompoundTag tag = readTag(server, name);
        ListTag size = tag.getList("size", Tag.TAG_INT);
        return new Vec3i(size.getInt(0), size.getInt(1), size.getInt(2));
    }

    /**
     * 展开蓝图为目标格集。
     *
     * @param anchor           落位基点 = 旋转后结构的最小角(x/y/z 最小处)
     * @param rotationQuarters 顺时针旋转的四分之一圈数(0-3)
     */
    public static Loaded load(ServerLevel level, String name, BlockPos anchor, int rotationQuarters) {
        CompoundTag tag = readTag(level.getServer(), name);
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        int sx = sizeTag.getInt(0);
        int sy = sizeTag.getInt(1);
        int sz = sizeTag.getInt(2);

        // 多调色板结构(沉船等)取第一板;常规结构用单板。
        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            paletteTag = tag.getList("palettes", Tag.TAG_LIST).getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        Rotation rotation = switch (Math.floorMod(rotationQuarters, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(
                    level.holderLookup(Registries.BLOCK), paletteTag.getCompound(i)).rotate(rotation));
        }

        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > MAX_CELLS) {
            throw new IllegalArgumentException("blueprint " + name + " has " + blocks.size()
                    + " cells, exceeding the " + MAX_CELLS + " cap");
        }
        int quarters = Math.floorMod(rotationQuarters, 4);
        List<BuildTaskRecord.Target> targets = new ArrayList<>(blocks.size());
        java.util.Map<Long, CompoundTag> beData = new java.util.HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag cell = blocks.getCompound(i);
            ListTag pos = cell.getList("pos", Tag.TAG_INT);
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            BlockState state = palette.get(cell.getInt("state"));
            // 能不能建走同一个判据(工具入口那边拿它当拒绝理由,这边拿它当跳过条件)
            if (com.dwinovo.numen.core.task.BuildStates.unbuildableReason(state) != null) {
                continue;
            }
            int rx;
            int rz;
            switch (quarters) {
                case 1 -> { rx = sz - 1 - z; rz = x; }
                case 2 -> { rx = sx - 1 - x; rz = sz - 1 - z; }
                case 3 -> { rx = z; rz = sx - 1 - x; }
                default -> { rx = x; rz = z; }
            }
            BlockPos world = anchor.offset(rx, y, rz);
            targets.add(new BuildTaskRecord.Target(state, state.getBlock().asItem(), world,
                    state.getBlock().builtInRegistryHolder().key().location().getPath(),
                    null, null, null));
            // 方块实体数据顺着同一条管线走:箱子里的东西、告示牌的字、旗帜的花纹
            if (cell.contains("nbt", Tag.TAG_COMPOUND)) {
                beData.put(world.asLong(), cell.getCompound("nbt").copy());
            }
        }
        Vec3i size = (quarters % 2 == 0) ? new Vec3i(sx, sy, sz) : new Vec3i(sz, sy, sx);
        return new Loaded(targets, size, beData);
    }

    private static CompoundTag readTag(MinecraftServer server, String name) {
        for (Path base : searchRoots(server)) {
            Path nbt = base.resolve(name + ".nbt");
            Path snbt = base.resolve(name + ".snbt");
            Path litematic = base.resolve(name + ".litematic");
            Path schem = base.resolve(name + ".schem");
            try {
                if (Files.exists(nbt)) {
                    return NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap());
                }
                if (Files.exists(snbt)) {
                    return NbtUtils.snbtToStructure(Files.readString(snbt));
                }
                // 社区格式:读出 gzip NBT 后转成原版结构形态,下游管线无感
                if (Files.exists(litematic)) {
                    return BlueprintFormats.fromLitematic(
                            NbtIo.readCompressed(litematic, NbtAccounter.unlimitedHeap()));
                }
                if (Files.exists(schem)) {
                    return BlueprintFormats.fromSchem(
                            NbtIo.readCompressed(schem, NbtAccounter.unlimitedHeap()));
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("blueprint " + name + " cannot be read: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("blueprint " + name + " not found; use blueprint_list first");
    }
}
