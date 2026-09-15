package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.hud.NumenHudToasts;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentReplyPayload;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.platform.Services;

import net.minecraft.client.resources.language.I18n;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边挂着的征询:每只同伴最多一条,照服务端推来的抄({@link ConsentRequestPayload}),
 * 撤回就删。答复框、世界轮廓、面板侧栏的标记与派发器的兜底豁免都只读这里。客户端主线程读写。
 */
public final class ConsentCards {

    private static final Map<UUID, ConsentRequestPayload> PENDING = new LinkedHashMap<>();

    private ConsentCards() {}

    /**
     * 网络处理体:一条请求顶掉这只同伴原来那条;撤回就删。她开始等主人时右上角弹一条"xxx 请求你的同意,按 [Y] 答复"
     * (同一只同伴换一条请求不再弹——她一直在等,不是又来了一个);主人没答就撤掉的(超时、任务结束……)再弹一条
     * 为什么——答复框是悄悄收起的,主人得知道那一问怎么了。
     */
    public static void accept(ConsentRequestPayload p) {
        if (!p.withdrawn()) {
            if (PENDING.put(p.companion(), p) == null) {
                NumenHudToasts.push(NumenToasts.Severity.WARN, I18n.get(ModLanguageData.Keys.CONSENT_ASKING,
                        name(p.companion()), NumenKeys.TALK_COMPANION.getTranslatedKeyMessage().getString()));
            }
            return;
        }
        if (PENDING.remove(p.companion()) != null && !p.withdrawnBecause().isEmpty()) {
            NumenHudToasts.push(NumenToasts.Severity.WARN, I18n.get(ModLanguageData.Keys.CONSENT_WITHDRAWN,
                    name(p.companion()), p.withdrawnBecause()));
        }
    }

    /** 花名册里的名字;不在花名册里是 "?"。 */
    static String name(UUID companion) {
        String name = NumenRoster.instance().name(companion);
        return name == null ? "?" : name;
    }

    /** 这只同伴挂着的那条;没有是 null。 */
    public static ConsentRequestPayload pending(UUID companion) {
        return companion == null ? null : PENDING.get(companion);
    }

    /** 最早挂上、还没答复的那条;没有是 null。 */
    public static ConsentRequestPayload first() {
        return PENDING.isEmpty() ? null : PENDING.values().iterator().next();
    }

    public static Collection<ConsentRequestPayload> all() {
        return PENDING.values();
    }

    /**
     * 主人答了:答复发回服务端,这条先删——服务端收到后推来的撤回找不到它,也就不再报原因。
     */
    public static void reply(ConsentRequestPayload p, ConsentAnswer.Decision decision, String note) {
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.length() > ConsentReplyPayload.MAX_NOTE_LENGTH) {
            trimmed = trimmed.substring(0, ConsentReplyPayload.MAX_NOTE_LENGTH);
        }
        Services.NETWORK.sendToServer(new ConsentReplyPayload(p.companion(), p.id(), decision, trimmed));
        PENDING.remove(p.companion(), p);
    }

    /** 断线:上一个世界的请求一条都不留。 */
    public static void clear() {
        PENDING.clear();
    }
}
