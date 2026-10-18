package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinSigning;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * 给她换一项绑定({@link Slot}:档案、人设、声线、皮肤)的唯一入口:面板的编辑卡({@link OwnerInput.Bind})和
 * {@code /numen companion} 命令都走这里。条目要在主人看得到、没被他关掉的库里({@link Library#find});绑的是哪一条记进她的
 * 绑定({@link CompanionLoop#bind}),皮肤另外要穿到身上(原地重建身体,背包位置在做的活都留着)。不绑({@code entry == null})
 * 是回到没配的样子:没有声线就不出声,没有皮肤就是默认皮肤。
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
            if (slot == Slot.SKIN) {
                Companions.applySkin(server, companion, null);
            }
            loop.get().bind(slot, null);
            return null;
        }
        Library.Entry found = LibraryHub.of(server).library(slot.kind(), owner).find(EntryId.parse(entry));
        if (found == null) {
            return "no such entry: " + entry;
        }
        if (slot == Slot.SKIN) {
            SkinProfile skin;
            try {
                skin = SkinProfile.read(found.text());
            } catch (RuntimeException unreadable) {
                return "unreadable skin: " + entry;
            }
            if (!skin.signed()) {
                return "that skin has no signed textures: " + entry;
            }
            Companions.applySkin(server, companion, new MojangSkins.Skin(skin.value(), skin.signature()));
        }
        loop.get().bind(slot, found.id().toString());
        return null;
    }

    /**
     * 一条皮肤的内容变了(签好写进货架、管理员采用新版或恢复默认):绑着它的在场同伴都按 {@link Companions#applySkin} 换上,
     * 不在场的下次重建时自然是新的——绑定与身体只认库里这一条。内置的条目所有人共用,谁名下的同伴都换;我的只换本人的。
     *
     * @param builtin 条目在内置组里
     */
    public static void skinChanged(MinecraftServer server, UUID owner, boolean builtin, String id) {
        CompanionLoops.boundTo(Slot.SKIN, id).forEach((companion, wearerOwner) -> {
            if (builtin || wearerOwner.equals(owner)) {
                apply(server, wearerOwner, companion, Slot.SKIN, id);
            }
        });
    }

    /** 一条皮肤签好写进了货架:绑着它旧名字的同伴改指新名字,再按 {@link #skinChanged} 换上新签出来的。 */
    static void skinSigned(MinecraftServer server, UUID owner, boolean builtin, SkinSigning.Outcome outcome) {
        if (!outcome.ok()) {
            return;
        }
        String id = outcome.id().toString();
        if (outcome.replaced() != null && !outcome.replaced().equals(outcome.id())) {
            if (builtin) {
                CompanionLoops.rebindAll(Slot.SKIN, outcome.replaced().toString(), id);
            } else {
                CompanionLoops.rebind(owner, Slot.SKIN, outcome.replaced().toString(), id);
            }
        }
        skinChanged(server, owner, builtin, id);
    }
}
