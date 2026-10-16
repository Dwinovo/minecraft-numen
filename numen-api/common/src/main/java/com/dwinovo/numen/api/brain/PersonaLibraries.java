package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每位主人在服务器上的人设库:同一位主人永远是同一份(她们每轮现取人设正文、界面经库协议改它,读写的是同一个对象),
 * 落在他的玩家目录里,写走他那条车道。关服时放手。
 */
public final class PersonaLibraries {

    private static final Map<UUID, PersonaLibrary> OPEN = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(OPEN::clear);
    }

    private PersonaLibraries() {}

    public static PersonaLibrary of(MinecraftServer server, UUID owner) {
        return OPEN.computeIfAbsent(owner, id -> {
            Home home = Homes.of(server).owner(id);
            return PersonaLibrary.at(home.file("persona"), home.io());
        });
    }
}
