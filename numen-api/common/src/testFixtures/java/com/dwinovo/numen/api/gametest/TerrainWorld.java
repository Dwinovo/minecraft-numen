package com.dwinovo.numen.api.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * GameTest 的测试世界:每次开跑都从干净的世界目录开始,真实地形维度 {@value #DIMENSION_ID} 的区域文件来自仓库里的固定存档,
 * 不在运行时现场生成(现场生成的树在区块边界上不确定,生成还要几十秒)。存档是一个目录,里面的 {@code region/*.mca}
 * 原样放进该维度的 region 目录;系统属性 {@value #SAVE} 指向它,没给就只清世界、不放存档。存档怎么做出来见
 * {@code numen/pathing/src/gametest/terrain/README.md}。
 */
public final class TerrainWorld {

    /** 真实地形维度的 id(数据包 {@code data/numen_test/dimension/terrain.json} 定义)。 */
    public static final String DIMENSION_ID = "numen_test:terrain";
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(DIMENSION_ID));
    /** 固定存档目录的系统属性。 */
    public static final String SAVE = "numen.gametest.terrain";

    /** 世界目录里不是世界内容的东西:会话锁(已被服务器持有)和加载器给这个世界放的配置、用户数据包。 */
    private static final Set<String> KEEP = Set.of("session.lock", "serverconfig", "datapacks");

    private TerrainWorld() {}

    /** 清空世界目录({@link #KEEP} 除外),再把固定存档的区域文件放进真实地形维度。服务器建世界之前调。 */
    public static void prepare(Path worldRoot) {
        try {
            try (Stream<Path> children = Files.list(worldRoot)) {
                for (Path child : children.filter(p -> !KEEP.contains(p.getFileName().toString())).toList()) {
                    delete(child);
                }
            }
            String save = System.getProperty(SAVE);
            if (save == null) {
                return;
            }
            Path regions = regionDir(worldRoot);
            Files.createDirectories(regions);
            try (Stream<Path> files = Files.list(Path.of(save).resolve("region"))) {
                for (Path file : files.toList()) {
                    Files.copy(file, regions.resolve(file.getFileName()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("准备测试世界失败:" + worldRoot, e);
        }
    }

    /** 真实地形维度的 region 目录。 */
    public static Path regionDir(MinecraftServer server) {
        return regionDir(server.getWorldPath(LevelResource.ROOT));
    }

    private static Path regionDir(Path worldRoot) {
        return DimensionType.getStorageFolder(DIMENSION, worldRoot).resolve("region");
    }

    private static void delete(Path path) throws IOException {
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
