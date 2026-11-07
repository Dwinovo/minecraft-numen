package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * 给她换一项绑定({@link Slot}:档案、人设、声线)的唯一入口:面板的编辑卡({@link OwnerInput.Bind})和
 * {@code /numen companion} 命令都走这里。条目要在主人看得到、没被他关掉的库里({@link Library#find});绑的是哪一条记进她的
 * 绑定({@link CompanionLoop#bind})。不绑({@code entry == null})是回到没配的样子:没有声线就不出声。皮肤不在这里——它不是库条目,
 * 换皮肤走 {@code Wardrobe}。
 *
 * <p>线程:服务端主线程。
 */
public final class CompanionBinding {

    private CompanionBinding() {}

    /**
     * @return 没办成的原因(英文开发文字,给日志和命令回显);办成是 {@code null}
     */
    public static String apply(MinecraftServer server, UUID owner, UUID companion, Slot slot, String entry) {
        Optional<CompanionLoop> loop = CompanionLoops.get(companion);
        if (loop.isEmpty()) {
            return "she has no brain here";
        }
        if (entry == null || entry.isBlank()) {
            loop.get().bind(slot, null);
            return null;
        }
        Library.Entry found = LibraryHub.of(server).library(slot.kind(), owner).find(EntryId.parse(entry));
        if (found == null) {
            return "no such entry: " + entry;
        }
        loop.get().bind(slot, found.id().toString());
        return null;
    }
}
