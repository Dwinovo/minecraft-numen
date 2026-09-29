package com.dwinovo.numen.area;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.cli.Names;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 每主人一份:他名下的区域,按名字存。区域归主人,不归哪一只同伴——同一主人的同伴都认得、都能读写,跟着存档走,重启不丢。
 * 存在主世界的存档数据里,文件名带主人 UUID,与 {@code PermissionStore} 同一个做法。
 *
 * <p>线程:主线程改。手里的区域表是不可变快照,每次改动换一份新的;权限层在主线程取走引用({@link #all}),
 * 搜索线程拿着读,不会读到改了一半的表,也不回头读这份存档。
 *
 * <p>读档时读不通的一块区域记一条错误日志、不进表,与读不通的权限规则同一个处理。
 */
public final class AreaStore extends SavedData {

    private static final SavedData.Factory<AreaStore> FACTORY = new SavedData.Factory<>(
            AreaStore::new, AreaStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private SortedMap<String, Area> areas = Collections.emptySortedMap();

    AreaStore() {
    }

    public static AreaStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_areas_" + owner);
    }

    static AreaStore load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries.lookupOrThrow(Registries.BLOCK));
    }

    static AreaStore load(CompoundTag tag, HolderGetter<Block> blocks) {
        AreaStore store = new AreaStore();
        TreeMap<String, Area> read = new TreeMap<>();
        CompoundTag all = tag.getCompound("areas");
        for (String name : all.getAllKeys()) {
            try {
                read.put(Names.checked("area", name), Area.load(all.getCompound(name), blocks));
            } catch (IllegalArgumentException e) {
                Constants.LOG.error("[numen-area] 区域 {} 读不通,没有载入: {}", name, e.getMessage());
            }
        }
        store.areas = Collections.unmodifiableSortedMap(read);
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        areas.forEach((name, area) -> all.put(name, area.save()));
        tag.put("areas", all);
        return tag;
    }

    /** 此刻全部区域,按名字排;不可变快照。 */
    public SortedMap<String, Area> all() {
        return areas;
    }

    /** 叫这个名字的区域;没有是 null。 */
    public Area get(String name) {
        return areas.get(name);
    }

    /**
     * 新建一块区域。
     *
     * @throws IllegalArgumentException 名字不合规矩,或已经有一块叫这个名字
     */
    public void create(String name, Area area) {
        Names.checked("area", name);
        if (areas.containsKey(name)) {
            throw new IllegalArgumentException("there is already an area named " + name);
        }
        write(name, area);
    }

    /**
     * 换掉一块已有区域的内容(加部分、删部分、刷新之后的新值)。
     *
     * @throws IllegalArgumentException 没有叫这个名字的区域
     */
    public void replace(String name, Area area) {
        if (!areas.containsKey(name)) {
            throw new IllegalArgumentException("there is no area named " + name);
        }
        write(name, area);
    }

    /**
     * 删掉一块区域。
     *
     * @return 删掉的那块;没有叫这个名字的是 null
     */
    public Area delete(String name) {
        if (!areas.containsKey(name)) {
            return null;
        }
        TreeMap<String, Area> next = new TreeMap<>(areas);
        Area removed = next.remove(name);
        areas = Collections.unmodifiableSortedMap(next);
        setDirty();
        return removed;
    }

    private void write(String name, Area area) {
        TreeMap<String, Area> next = new TreeMap<>(areas);
        next.put(name, area);
        areas = Collections.unmodifiableSortedMap(next);
        setDirty();
    }
}
