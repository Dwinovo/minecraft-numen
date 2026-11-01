package com.dwinovo.numen.pathing.benchmark;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.dwinovo.numen.pathing.Vanilla;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 把数据集里每块地的 {@code .mca} 导成中立地形格式(见数据集的 FORMAT.md 与 RULES.md),用法:
 * {@code Export <数据集目录> <输出目录>},输出 {@code neutral/classes.json} 与 {@code neutral/<地>/{grid.bin.gz,grid.json,blockmap.csv}}。
 *
 * <p>格子下标 {@code ((x·Z)+z)·Y + y}(局部坐标,y 最快,x、z 从区域西北角起,y 从最低高度起),每格一个字节的类别码,
 * 方块状态到类别由 {@link Neutral#of} 算,没有手写的方块表。区域里没写的区段与区块是空气;整块区块缺失说明存档不完整,直接报错。
 */
final class Export {

    /** 给人读的两份 JSON(类别表、网格说明)缩进输出。 */
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Export() {}

    public static void main(String[] args) throws IOException {
        Vanilla.boot();
        Dataset dataset = new Dataset(Path.of(args[0]));
        Path out = Path.of(args[1]).resolve("neutral");
        Files.createDirectories(out);
        write(out.resolve("classes.json"), PRETTY.toJson(classes()) + "\n");
        for (Dataset.Region region : dataset.regions()) {
            long t0 = System.currentTimeMillis();
            RegionWorld world = RegionWorld.load(region.name(), dataset.save(region.name()));
            export(world, out.resolve(region.name()));
            System.out.println(region.name() + " 导出完成 " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    /** 类别表:{@code classes.json}。 */
    static JsonObject classes() {
        JsonObject root = new JsonObject();
        root.addProperty("rules", Neutral.RULES_VERSION);
        JsonArray list = new JsonArray();
        for (Neutral.Cls cls : Neutral.Cls.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("code", cls.code());
            o.addProperty("name", cls.key);
            o.addProperty("passable", cls.passable);
            o.addProperty("support", cls.support);
            o.addProperty("swim", cls.swim);
            o.addProperty("climb", cls.climb);
            o.addProperty("dig", cls.dig);
            o.addProperty("place", cls.place);
            o.addProperty("about", cls.about);
            list.add(o);
        }
        root.add("classes", list);
        return root;
    }

    private static void export(RegionWorld world, Path dir) throws IOException {
        Files.createDirectories(dir);
        int sizeX = RegionWorld.REGION * 16;
        int sizeZ = sizeX;
        int sizeY = world.heights.getHeight();
        int minY = world.heights.getMinBuildHeight();
        byte[] grid = new byte[Math.multiplyExact(Math.multiplyExact(sizeX, sizeZ), sizeY)];
        Map<BlockState, Neutral.Cls> classOf = new IdentityHashMap<>();
        Map<BlockState, long[]> census = new IdentityHashMap<>();
        for (int cx = 0; cx < RegionWorld.REGION; cx++) {
            for (int cz = 0; cz < RegionWorld.REGION; cz++) {
                LevelChunkSection[] sections = world.of(world.regionX * RegionWorld.REGION + cx, world.regionZ * RegionWorld.REGION + cz);
                if (sections == null) {
                    throw new IllegalStateException(world.name + " 缺区块 " + cx + "," + cz + ",存档不完整");
                }
                for (int s = 0; s < sections.length; s++) {
                    LevelChunkSection section = sections[s];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int y0 = s * 16;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int column = ((cx * 16 + x) * sizeZ + cz * 16 + z) * sizeY + y0;
                            for (int y = 0; y < 16; y++) {
                                BlockState state = section.getBlockState(x, y, z);
                                Neutral.Cls cls = classOf.computeIfAbsent(state, Neutral::of);
                                census.computeIfAbsent(state, k -> new long[1])[0]++;
                                grid[column + y] = (byte) cls.code();
                            }
                        }
                    }
                }
            }
        }
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve("grid.bin.gz")), 1 << 16), 1 << 16)) {
            out.write(grid);
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("rules", Neutral.RULES_VERSION);
        meta.addProperty("name", world.name);
        meta.addProperty("sizeX", sizeX);
        meta.addProperty("sizeZ", sizeZ);
        meta.addProperty("sizeY", sizeY);
        meta.addProperty("minY", minY);
        meta.addProperty("originX", world.minX());
        meta.addProperty("originZ", world.minZ());
        meta.addProperty("dimension", world.dimension);
        meta.addProperty("minecraft", "1.21.1");
        write(dir.resolve("grid.json"), PRETTY.toJson(meta) + "\n");
        // 方块状态 → 类别码,带这块地里的格数;状态文本带逗号所以加引号,按文本排序,输出不随运行变
        Map<String, String> rows = new TreeMap<>();
        for (Map.Entry<BlockState, long[]> e : census.entrySet()) {
            Neutral.Cls cls = classOf.get(e.getKey());
            rows.put(BlockStateParser.serialize(e.getKey()), cls.code() + "," + cls.key + "," + e.getValue()[0]);
        }
        List<String> lines = new ArrayList<>();
        lines.add("state,class,name,cells");
        rows.forEach((state, rest) -> lines.add("\"" + state + "\"," + rest));
        write(dir.resolve("blockmap.csv"), String.join("\n", lines) + "\n");
    }

    private static void write(Path file, String text) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(text);
        }
    }
}
