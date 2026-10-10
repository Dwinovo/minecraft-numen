package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.TerrainWorld;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/**
 * 固定存档的描述({@code terrain.properties},制作时写出)和对存档的两道核对:路线必须落在存档的区域里,
 * 读到的必须是存档而不是现场生成的地形。
 */
final class TerrainBlock {

    /** 区域正中的区块,票据压在这里。 */
    ChunkPos center() {
        return new ChunkPos(regionX * 32 + 15, regionZ * 32 + 15);
    }

    /** 路线两端离区域边缘至少这么多区块:路线的加载范围要整个落在存档里,边缘外的区块存档里没有。 */
    private static final int MARGIN_CHUNKS = 4;

    /**
     * 加载票据的距离:压在区域正中的区块上,往外 13 圈到 FULL、12 圈实体能刻(身体靠这个才会动)。12 圈是区块 3..27,
     * 罩住 {@link #MARGIN_CHUNKS} 圈以内的整片内部(区块 4..27),路线绕再远的路也不出这片。
     */
    static final int LOAD_DISTANCE = 14;
    /** 必须加载好的半径(实体能刻的那一圈)。 */
    static final int LOAD_RADIUS = LOAD_DISTANCE - 2;

    private record Sentinel(BlockPos pos, Block block) {}

    private final int regionX;
    private final int regionZ;
    private final List<Sentinel> sentinels = new ArrayList<>();

    private TerrainBlock(Properties props) {
        String[] region = props.getProperty("region").split(",");
        this.regionX = Integer.parseInt(region[0].trim());
        this.regionZ = Integer.parseInt(region[1].trim());
        for (int i = 0; props.containsKey("sentinel." + i); i++) {
            String[] f = props.getProperty("sentinel." + i).split(",");
            sentinels.add(new Sentinel(new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])),
                    BuiltInRegistries.BLOCK.get(ResourceLocation.parse(f[3]))));
        }
    }

    /** 这次运行加载的那块存档的描述;没有存档(没给系统属性)就是运行配置错了。 */
    static TerrainBlock load() {
        String save = System.getProperty(TerrainWorld.SAVE);
        if (save == null) {
            throw new GameTestAssertException("运行配置没有给固定存档(系统属性 " + TerrainWorld.SAVE + ")");
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(save).resolve("terrain.properties"))) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TerrainBlock(props);
    }

    /** 路线两端离区域边缘都够远,否则路线的加载范围会伸到存档外面,去现场生成。 */
    void requireInside(TerrainRoutes.Route route) {
        for (BlockPos pos : List.of(route.from(), route.to())) {
            int margin = MARGIN_CHUNKS * 16;
            int minX = regionX * 512 + margin;
            int minZ = regionZ * 512 + margin;
            if (pos.getX() < minX || pos.getX() >= minX + 512 - 2 * margin || pos.getZ() < minZ
                    || pos.getZ() >= minZ + 512 - 2 * margin) {
                throw new GameTestAssertException("路线 " + route.name() + " 的 " + pos.toShortString() + " 离存档区域 r."
                        + regionX + "." + regionZ + " 的边缘不到 " + MARGIN_CHUNKS + " 个区块");
            }
        }
    }

    /** 哨兵方块都在:现场生成的地形不会有它们。 */
    void requireFromSave(ServerLevel level) {
        for (Sentinel s : sentinels) {
            if (!level.getBlockState(s.pos()).is(s.block())) {
                throw new GameTestAssertException("读到的不是固定存档:" + s.pos().toShortString() + " 应是 "
                        + BuiltInRegistries.BLOCK.getKey(s.block()) + ",却是 " + level.getBlockState(s.pos()));
            }
        }
    }
}
