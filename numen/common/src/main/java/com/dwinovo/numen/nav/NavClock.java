package com.dwinovo.numen.nav;

import com.dwinovo.numen.pathing.search.SearchClock;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 同伴的寻路看哪块表({@link SearchClock}):跟着服务器实例走的一份,挂在主世界的数据存储上,服务器在它在,服务器没了它也没了。
 * Numen 侧只在这里决定:正式运行是墙钟;GameTest 服务器起好时由测试夹具换成按做了多少活折算的表({@link #use}),
 * 用例的结论与机器快慢无关。搜索拿到的表经 {@link CompanionPorts} 交进寻路的端口,模块那边只有这一个接缝。
 *
 * <p>不写盘:表不是世界的一部分,从不标脏;读到的(本来也没有)一律是一块新的墙钟表。
 */
public final class NavClock extends SavedData {

    private static final net.minecraft.resources.Identifier NAME = net.minecraft.resources.Identifier.fromNamespaceAndPath("numen", "nav_clock");
    private static final SavedDataType<NavClock> TYPE = new SavedDataType<>(NAME, NavClock::new,
            MapCodec.unitCodec(NavClock::new), DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private SearchClock clock = SearchClock.WALL;

    private NavClock() {}

    private static NavClock get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** 这台服务器上同伴的寻路看的表。 */
    static SearchClock of(MinecraftServer server) {
        return get(server).clock;
    }

    /** 这台服务器上同伴的寻路改看 {@code clock}:GameTest 服务器起好时由测试夹具调。 */
    public static void use(MinecraftServer server, SearchClock clock) {
        get(server).clock = clock;
    }

}
