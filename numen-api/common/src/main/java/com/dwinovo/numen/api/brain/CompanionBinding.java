package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 给她换一项绑定({@link Slot}:档案、人设、声线)的唯一入口:面板的编辑卡({@link OwnerInput.Bind})、
 * {@code /numen companion} 命令和召唤时一并定下的绑定({@code SummonSetup})都走这里。条目要在<b>动手绑的那个人</b>看得到、没被他关掉的
 * 库里({@link Library#find})——谁都只能绑自己看得到的;绑的是哪一条、是谁绑的一起记进她的绑定({@link CompanionLoop#bind}),
 * 之后解析、钥匙、记账都跟着他({@link Bound})。不绑({@code entry == null})是回到没配的样子:没有声线就不出声。皮肤不在这里——
 * 它不是库条目,换皮肤走 {@code Wardrobe}。
 *
 * <p>线程:服务端主线程。
 */
public final class CompanionBinding {

    private CompanionBinding() {}

    /**
     * @param by 动手绑的人(她的主人之一;是不是由调用方判)
     * @return 没办成的原因(英文开发文字,给日志和命令回显);办成是 {@code null}
     */
    public static String apply(MinecraftServer server, UUID by, UUID companion, Slot slot, String entry) {
        Optional<CompanionLoop> loop = CompanionLoops.get(companion);
        if (loop.isEmpty()) {
            return "she has no brain here";
        }
        if (entry == null || entry.isBlank()) {
            loop.get().bind(slot, null);
            return null;
        }
        Library.Entry found = find(server, by, slot, entry);
        if (found == null) {
            return "no such entry: " + entry;
        }
        loop.get().bind(slot, new Bound(by, found.id()));
        return null;
    }

    /**
     * 这个人能绑的这一条:在他看得到、没被他关掉的库里;没有是 {@code null}。改绑({@link #apply})和召唤前的检查
     * ({@code SummonSetup})认的是同一条。
     */
    public static Library.Entry find(MinecraftServer server, UUID by, Slot slot, String entry) {
        return LibraryHub.of(server).library(slot.kind(), by).find(EntryId.parse(entry));
    }

    /**
     * 这个人不再是她的主人了:他绑的那几项退回未绑定——她不该再用他的库、他的 key、记他的账。
     *
     * @return 退掉了哪几项(调用方据此告诉在场的主人少了什么);名册上没有她、或他什么都没绑是空表
     */
    public static List<Slot> releaseBoundBy(MinecraftServer server, UUID companion, UUID person) {
        CompanionLoop loop = CompanionBrains.ensure(server, companion);
        return loop == null ? List.of() : loop.releaseBoundBy(person);
    }
}
