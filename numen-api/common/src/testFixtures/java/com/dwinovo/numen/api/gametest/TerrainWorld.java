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
 * GameTest 的测试世界:每次开跑都从干净的世界目录开始;数据包定义的真实地形维度({@value #DIMENSION_ID} 主世界生成器、{@value #NETHER_ID} 下界生成器)
 * 只用来制作固定存档(规划评测的数据集,见 {@code numen/pathing/src/gametest/terrain/README.md}),存档里的地形不在运行时现场生成。
 */
public final class TerrainWorld {

    /** 真实地形维度的 id(数据包 {@code data/numen_test/dimension/terrain.json} 定义)。 */
    public static final String DIMENSION_ID = "numen_test:terrain";
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(DIMENSION_ID));
    /** 下界地形维度的 id(数据包 {@code data/numen_test/dimension/terrain_nether.json}):原版下界的生成器与群系,制作下界的存档用。 */
    public static final String NETHER_ID = "numen_test:terrain_nether";
    public static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(NETHER_ID));
    /** 世界目录里不是世界内容的东西:会话锁(已被服务器持有)和加载器给这个世界放的配置、用户数据包。 */
    private static final Set<String> KEEP = Set.of("session.lock", "serverconfig", "datapacks");

    private TerrainWorld() {}

    /** 清空世界目录({@link #KEEP} 除外)。服务器建世界之前调。 */
    public static void prepare(Path worldRoot) {
        try (Stream<Path> children = Files.list(worldRoot)) {
            for (Path child : children.filter(p -> !KEEP.contains(p.getFileName().toString())).toList()) {
                delete(child);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("准备测试世界失败:" + worldRoot, e);
        }
    }

    /** 地形维度 {@code dimension} 的 region 目录。 */
    public static Path regionDir(MinecraftServer server, ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT)).resolve("region");
    }

    private static void delete(Path path) throws IOException {
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
