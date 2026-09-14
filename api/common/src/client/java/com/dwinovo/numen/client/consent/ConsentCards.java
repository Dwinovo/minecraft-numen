package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.network.payload.ConsentReplyPayload;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.platform.Services;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边挂着的征询:每只同伴最多一条,照服务端推来的抄({@link ConsentRequestPayload}),
 * 撤回就删。卡片、HUD、世界轮廓与派发器的兜底豁免都只读这里。客户端主线程读写。
 */
public final class ConsentCards {

    private static final Map<UUID, ConsentRequestPayload> PENDING = new LinkedHashMap<>();

    private ConsentCards() {}

    /** 网络处理体:一条请求顶掉这只同伴原来那条;撤回就删。 */
    public static void accept(ConsentRequestPayload p) {
        if (p.withdrawn()) {
            PENDING.remove(p.companion());
        } else {
            PENDING.put(p.companion(), p);
        }
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
     * 主人按了键:答复发回服务端,卡片先收起——服务端收到后会推撤回,那条到了也是删同一条。
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
