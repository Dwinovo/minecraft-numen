package com.dwinovo.numen.nav;

import com.dwinovo.numen.pathing.search.SearchClock;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 同伴的寻路看哪块表({@link SearchClock}):跟着服务器实例走的一份,挂在主世界的数据存储上,服务器在它在,服务器没了它也没了。
 * Numen 侧只在这里决定:正式运行是墙钟;GameTest 服务器起好时由测试夹具换成按做了多少活折算的表({@link #use}),
 * 用例的结论与机器快慢无关。搜索拿到的表经 {@link CompanionPorts} 交进寻路的端口,模块那边只有这一个接缝。
 *
 * <p>不写盘:表不是世界的一部分,{@link #save} 什么也不存,也从不标脏。
 */
public final class NavClock extends SavedData {

    private static final String NAME = "numen_nav_clock";
    private static final SavedData.Factory<NavClock> FACTORY = new SavedData.Factory<>(NavClock::new,
            (tag, registries) -> new NavClock(), net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private SearchClock clock = SearchClock.WALL;

    private NavClock() {}

    private static NavClock get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 这台服务器上同伴的寻路看的表。 */
    static SearchClock of(MinecraftServer server) {
        return get(server).clock;
    }

    /** 这台服务器上同伴的寻路改看 {@code clock}:GameTest 服务器起好时由测试夹具调。 */
    public static void use(MinecraftServer server, SearchClock clock) {
        get(server).clock = clock;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return tag;
    }
}
