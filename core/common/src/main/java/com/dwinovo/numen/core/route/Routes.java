package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.core.Constants;
import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 一位主人的路线,跟着存档与主人走:主世界的 SavedData,文件名带主人 UUID(与权限层的 {@code PermissionStore} 同一个做法)。
 * 同一个主人的同伴都认得、都能改;重启不丢。路线按名字存,名字是它唯一的称呼。
 */
public final class Routes extends SavedData {

    private static final String ROUTES = "routes";
    private static final Codec<List<Itinerary>> CODEC = Itinerary.CODEC.listOf();

    private static final SavedData.Factory<Routes> FACTORY = new SavedData.Factory<>(
            Routes::new, Routes::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /** 名字 → 路线,按建的先后。 */
    private final Map<String, Itinerary> routes = new LinkedHashMap<>();

    Routes() {
    }

    public static Routes of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_routes_" + owner);
    }

    static Routes load(CompoundTag tag, HolderLookup.Provider registries) {
        Routes loaded = new Routes();
        if (tag.contains(ROUTES)) {
            CODEC.parse(NbtOps.INSTANCE, tag.get(ROUTES))
                    .resultOrPartial(e -> Constants.LOG.error("[numen-route] 路线存档有读不懂的内容: {}", e))
                    .ifPresent(list -> list.forEach(r -> loaded.routes.put(r.name(), r)));
        }
        return loaded;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, all()).result().ifPresent(list -> tag.put(ROUTES, list));
        return tag;
    }

    /** 叫这个名字的那条;没有为 null。 */
    public Itinerary get(String name) {
        return routes.get(name);
    }

    /** 全部路线,按建的先后。 */
    public List<Itinerary> all() {
        return new ArrayList<>(routes.values());
    }

    /** 存下这一条:同名的换掉,新名字接在最后。 */
    public void put(Itinerary route) {
        routes.put(route.name(), route);
        setDirty();
    }

    /**
     * 记下对着 {@code planned} 这份意图做的计划。规划在后台跑,其间路线可能被改过(加减途经点、改规格)或删掉:那样的计划是对着
     * 旧意图做的,不记。
     */
    public void plan(Itinerary planned, Plan plan) {
        Itinerary latest = routes.get(planned.name());
        if (latest != null && latest.legs().equals(planned.legs()) && latest.flags().equals(planned.flags())) {
            put(latest.planned(plan));
        }
    }

    /** 删掉;没有这一条返回 false。 */
    public boolean remove(String name) {
        boolean removed = routes.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
