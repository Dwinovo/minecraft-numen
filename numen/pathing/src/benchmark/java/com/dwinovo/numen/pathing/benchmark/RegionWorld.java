package com.dwinovo.numen.pathing.benchmark;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.mojang.serialization.Codec;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * 一块固定存档读进内存:区域文件({@code r.X.Z.mca},32×32 个区块)用原版自己的 {@link RegionFile} 与 {@link NbtIo} 读,
 * 方块段按原版区块序列化器的同一份调色板编解码({@link PalettedContainer#codecRW}、生物群系的 {@code codecRO})解开,
 * 和服务器读存档是同一套代码。读出来的是 {@link WorldSnapshot.Sections}:寻路的快照直接从这里取方块段,不要服务器、不要 {@code ServerLevel}。
 *
 * <p>区域之外的区块没有(存档里不留邻居),答"没加载":搜索碰到区域边缘就停在边上,和真服务器上区域边缘外没加载的区块一样。
 * 哨兵方块({@code terrain.properties})在就说明读到的是这份存档。
 */
final class RegionWorld implements WorldSnapshot.Sections {

    /** 区域的边长(区块)。 */
    static final int REGION = 32;
    /** 搜索一次拷的半径:与 {@code drive.Driver} 派发搜索时用的同一个。 */
    static final int SEARCH_RADIUS = WorldSnapshot.SEARCH_RADIUS;
    /** 整块区域一次拷下的半径:以区域中心的区块为中心,罩住整个区域。 */
    private static final int WHOLE_RADIUS = REGION / 2;

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    /** 生物群系注册表:从原版数据包的世界生成数据读出(与服务器启动时读的同一份),方块段构造要它。 */
    private static final Registry<Biome> BIOMES = loadBiomes();
    private static final Codec<PalettedContainerRO<Holder<Biome>>> BIOME_CODEC = PalettedContainer.codecRO(BIOMES.asHolderIdMap(),
            BIOMES.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, BIOMES.getHolderOrThrow(Biomes.PLAINS));

    final String name;
    final String dimension;
    final int regionX;
    final int regionZ;
    final LevelHeightAccessor heights;
    final boolean ultraWarm;
    /** 找站得住的格从这一层往下扫:下界的基岩顶盖之下。 */
    final int scanTop;
    private final LevelChunkSection[][] chunks = new LevelChunkSection[REGION * REGION][];

    private RegionWorld(String name, Properties props) {
        this.name = name;
        this.dimension = props.getProperty("dimension");
        String[] region = props.getProperty("region").split(",");
        this.regionX = Integer.parseInt(region[0].trim());
        this.regionZ = Integer.parseInt(region[1].trim());
        this.ultraWarm = Boolean.parseBoolean(props.getProperty("ultra_warm", "false"));
        this.scanTop = Integer.parseInt(props.getProperty("scan_top"));
        this.heights = LevelHeightAccessor.create(Integer.parseInt(props.getProperty("min_y")), Integer.parseInt(props.getProperty("height")));
    }

    /** 读 {@code saveDir}(含 {@code terrain.properties} 与 {@code region/})里的整块存档。 */
    static RegionWorld load(String name, Path saveDir) {
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(saveDir.resolve("terrain.properties"))) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RegionWorld world = new RegionWorld(name, props);
        Path file = saveDir.resolve("region").resolve("r." + world.regionX + "." + world.regionZ + ".mca");
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(world.dimension));
        try (RegionFile region = new RegionFile(new RegionStorageInfo("benchmark", dimension, "chunk"), file, file.getParent(), false)) {
            // 区域文件一次只给一个线程读;解方块段是大头,按区块并行
            List<CompoundTag> tags = new java.util.ArrayList<>(REGION * REGION);
            for (int i = 0; i < REGION * REGION; i++) {
                ChunkPos pos = world.chunkPos(i);
                try (DataInputStream stream = region.getChunkDataInputStream(pos)) {
                    tags.add(stream == null ? null : NbtIo.read(stream));
                }
            }
            IntStream.range(0, REGION * REGION).parallel().forEach(i -> world.chunks[i] = world.decode(tags.get(i)));
        } catch (IOException e) {
            throw new UncheckedIOException("读不了区域文件 " + file, e);
        }
        world.requireSentinels(props);
        return world;
    }

    private ChunkPos chunkPos(int index) {
        return new ChunkPos(regionX * REGION + index % REGION, regionZ * REGION + index / REGION);
    }

    /** 一个区块的方块段:下标是 {@code 区段 y − 最低区段};存档里没写的区段(全空)为 null。 */
    private LevelChunkSection[] decode(CompoundTag chunk) {
        if (chunk == null) {
            return null;
        }
        if (chunk.getInt("DataVersion") != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            throw new IllegalStateException("存档的数据版本 " + chunk.getInt("DataVersion") + " 不是当前原版的,先升级再用");
        }
        LevelChunkSection[] sections = new LevelChunkSection[heights.getSectionsCount()];
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            int index = tag.getByte("Y") - heights.getMinSection();
            if (index < 0 || index >= sections.length) {
                continue;
            }
            PalettedContainer<BlockState> states = tag.contains("block_states", Tag.TAG_COMPOUND)
                    ? BLOCK_STATES.parse(NbtOps.INSTANCE, tag.getCompound("block_states")).getOrThrow(IllegalStateException::new)
                    : new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            PalettedContainerRO<Holder<Biome>> biomes = tag.contains("biomes", Tag.TAG_COMPOUND)
                    ? BIOME_CODEC.parse(NbtOps.INSTANCE, tag.getCompound("biomes")).getOrThrow(IllegalStateException::new)
                    : new PalettedContainer<>(BIOMES.asHolderIdMap(), BIOMES.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            sections[index] = new LevelChunkSection(states, biomes);
        }
        return sections;
    }

    private void requireSentinels(Properties props) {
        WorldSnapshot view = whole();
        for (int i = 0; props.containsKey("sentinel." + i); i++) {
            String[] f = props.getProperty("sentinel." + i).split(",");
            BlockPos pos = new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(f[3]));
            if (!view.getBlockState(pos).is(block)) {
                throw new IllegalStateException("读到的不是固定存档 " + name + ":" + pos.toShortString() + " 应是 " + f[3] + ",却是 " + view.getBlockState(pos));
            }
        }
    }

    @Override
    public LevelChunkSection[] of(int chunkX, int chunkZ) {
        int i = chunkX - regionX * REGION;
        int j = chunkZ - regionZ * REGION;
        return i < 0 || i >= REGION || j < 0 || j >= REGION ? null : chunks[j * REGION + i];
    }

    /** 区域的方块范围(含两端)。 */
    int minX() {
        return regionX * REGION * 16;
    }

    int minZ() {
        return regionZ * REGION * 16;
    }

    int maxX() {
        return minX() + REGION * 16 - 1;
    }

    int maxZ() {
        return minZ() + REGION * 16 - 1;
    }

    /** 为从 {@code from} 出发的一次搜索拷的快照:与真实运行时同一个半径。 */
    WorldSnapshot snapshot(BlockPos from) {
        return WorldSnapshot.capture(heights, new WorldBorder(), ultraWarm, this, from.getX() >> 4, from.getZ() >> 4, SEARCH_RADIUS);
    }

    /** 为从 {@code from} 出发的搜索拷的快照,窗口是不是伸出了区域(区域之外永远没有加载的区块)。 */
    boolean beyond(BlockPos from) {
        int cx = from.getX() >> 4;
        int cz = from.getZ() >> 4;
        return cx - SEARCH_RADIUS < regionX * REGION || cx + SEARCH_RADIUS >= (regionX + 1) * REGION
                || cz - SEARCH_RADIUS < regionZ * REGION || cz + SEARCH_RADIUS >= (regionZ + 1) * REGION;
    }

    /** 整块区域一次拷下的快照(标准答案求解器用)。 */
    WorldSnapshot whole() {
        return WorldSnapshot.capture(heights, new WorldBorder(), ultraWarm, this, regionX * REGION + REGION / 2, regionZ * REGION + REGION / 2,
                WHOLE_RADIUS);
    }

    private static Registry<Biome> loadBiomes() {
        try (MultiPackResourceManager data = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            RegistryAccess.Frozen loaded = RegistryDataLoader.load(data, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
            return loaded.registryOrThrow(Registries.BIOME);
        }
    }
}
