package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.brain.Segments;
import com.dwinovo.numen.api.brain.SkinMigration;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.SkinHandoverPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端交还的旧皮肤原图({@code SkinHandover})落进这台机器的皮肤文件夹:按序收齐一张就存一张(同名同内容的不再多放一份),
 * 存完在聊天栏说一句,免得玩家奇怪文件夹里多了图。段序不对这一张作废,服务端没发完的下次连上接着发。
 *
 * <p>线程:客户端主线程。
 */
final class SkinHandovers {

    private static final Map<String, Segments.Assembler> INCOMING = new HashMap<>();

    private SkinHandovers() {}

    /** 服务端推来一段。 */
    static void receive(SkinHandoverPayload segment) {
        Segments.Assembler parts = INCOMING.computeIfAbsent(segment.name(), name -> new Segments.Assembler());
        if (!parts.accept(segment.index(), segment.total(), segment.bytes())) {
            INCOMING.remove(segment.name());
            Constants.LOG.warn("[numen-skin] 服务端交还的皮肤 {} 段序不对,这一张作废", segment.name());
            return;
        }
        if (parts.complete()) {
            INCOMING.remove(segment.name());
            String saved = SkinMigration.place(NumenPaths.skins(), segment.name(), parts.bytes());
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(Component.translatable(Keys.SKIN_HANDOVER, saved));
            }
        }
    }

    /** 断线:收了一半的作废。 */
    static void reset() {
        INCOMING.clear();
    }
}
