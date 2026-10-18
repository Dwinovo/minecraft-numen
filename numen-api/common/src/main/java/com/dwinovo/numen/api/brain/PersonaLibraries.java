package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 每位主人在这台服务器上的人设库:同一位主人永远是同一份内置加我的(她们每轮现取人设正文、界面经库协议改它,读写的是同一个
 * {@link com.dwinovo.numen.api.library.Library})。
 */
public final class PersonaLibraries {

    private PersonaLibraries() {}

    public static PersonaLibrary of(MinecraftServer server, UUID owner) {
        return new PersonaLibrary(LibraryHub.of(server).library(PersonaLibrary.KIND, owner));
    }
}
