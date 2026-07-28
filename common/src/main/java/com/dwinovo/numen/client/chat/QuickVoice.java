package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.stt.VoiceInputController;
import com.dwinovo.numen.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 快捷语音:对讲机式按住说话,松开把最终转写直接发给当前交互对象
 * ({@link SelectedCompanion#resolveTarget()}),走与文字完全相同的
 * {@link NumenGateway#enqueue} 管线。录音期间准星提示层显示实时增量,
 * 目标在按下那一刻锁定——说到一半转头不换收件人。
 */
public final class QuickVoice {

    private static boolean recording;
    private static NumenRoster.Entry target;
    private static String livePartial = "";
    private static String notice;
    private static long noticeUntilMs;

    private QuickVoice() {}

    /** 按键按下(NumenKeys 轮询的按下沿)。 */
    public static void press() {
        if (recording) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        NumenRoster.Entry t = SelectedCompanion.resolveTarget();
        if (t == null) {
            flash("先按轮盘键选一位同伴,或把准星对准它");
            return;
        }
        target = t;
        livePartial = "";
        boolean ok = VoiceInputController.start(Services.CONFIG,
                partial -> livePartial = partial,
                QuickVoice::deliver,
                status -> { recording = false; flash(status); });
        recording = ok;
        if (!ok) {
            target = null;
        }
    }

    /** 按键松开:停采集,最终转写异步回 {@link #deliver}。 */
    public static void release() {
        if (recording) {
            recording = false;
            VoiceInputController.stop();
        }
    }

    private static void deliver(String text) {
        NumenRoster.Entry t = target;
        target = null;
        livePartial = "";
        recording = false;
        String said = text == null ? "" : text.trim();
        if (t == null || said.isEmpty()) {
            if (said.isEmpty()) flash("没听清,再试一次");
            return;
        }
        boolean accepted = NumenGateway.enqueue(t.uuid(), said);
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(
                accepted ? "[→ " + t.name() + "] (语音) " + said
                         : "[" + t.name() + "] (没能送达——它可能不在线)"));
    }

    /** 录音中(驱动提示层的红点)。 */
    public static boolean isRecording() {
        return recording;
    }

    /** 提示层文案:录音时的实时状态,或短暂的错误提示;没有就 null。 */
    public static String hudLine() {
        if (recording && target != null) {
            String live = livePartial.isBlank() ? "" : ":" + livePartial;
            return "● 正在听" + live + "  (松开发给 " + target.name() + ")";
        }
        if (notice != null && System.currentTimeMillis() < noticeUntilMs) {
            return notice;
        }
        return null;
    }

    private static void flash(String text) {
        notice = text;
        noticeUntilMs = System.currentTimeMillis() + 3000;
    }

    public static void clear() {
        recording = false;
        target = null;
        livePartial = "";
        notice = null;
    }
}
