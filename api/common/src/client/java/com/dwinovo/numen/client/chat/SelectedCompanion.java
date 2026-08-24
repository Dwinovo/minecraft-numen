package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.agent.NumenRoster;

import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 当前交互对象:同伴轮盘选中的那一位,快捷对话/快捷语音的收件人。
 * 会话态,断线清空。
 *
 * <p>目标解析优先级(所见即所说):准星正指着的同伴 &gt; 轮盘选中
 * &gt; 唯一同伴自动选中。全落空返回 null——多同伴又没选过人时,
 * 快捷键会提示先开轮盘。客户端主线程专用。
 */
public final class SelectedCompanion {

    private static UUID selected;

    private SelectedCompanion() {}

    public static void set(UUID uuid) {
        selected = uuid;
    }

    public static UUID get() {
        return selected;
    }

    /** 快捷键此刻应该对谁说话。 */
    public static NumenRoster.Entry resolveTarget() {
        AbstractClientPlayer aimed = CompanionChatScreen.crosshairCompanion();
        if (aimed != null) {
            NumenRoster.Entry e = entryOf(aimed.getUUID());
            if (e != null) return e;
        }
        if (selected != null) {
            NumenRoster.Entry e = entryOf(selected);
            if (e != null) return e;
        }
        List<NumenRoster.Entry> all = NumenRoster.instance().entries();
        return all.size() == 1 ? all.get(0) : null;
    }

    private static NumenRoster.Entry entryOf(UUID uuid) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (e.uuid().equals(uuid)) return e;
        }
        return null;
    }

    public static void clear() {
        selected = null;
    }
}
