package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.platform.services.INumenConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.function.Consumer;

/**
 * 麦克风按钮的胶水:{@link #toggle} 一下开录、再一下停。串起
 * {@link SttProviders#fromConfig}(建后端)、{@link MicrophoneManager}(采集)、
 * {@link SttListener}(回结果)。转写文本经 {@code onText} 刷输入框——批量在结尾
 * 一次刷,流式边说边刷,按钮不关心是哪种。所有 UI 回调切回客户端主线程。
 */
public final class VoiceInputController {

    private static volatile SttSession session;
    private static volatile boolean active;

    private VoiceInputController() {}

    public static boolean isActive() {
        return active || MicrophoneManager.isRecording();
    }

    /**
     * 对讲机式:按下开录,{@link #stop()} 松开收音。与 {@link #toggle} 的差别
     * 只在回调口径——增量与最终分开,方便"松开后拿最终转写直接发送"的用法。
     * 已在录音中则直接返回 false(两条路共用一只麦克风,不并录)。
     */
    public static synchronized boolean start(INumenConfig cfg, Consumer<String> onPartial,
                                             Consumer<String> onFinal, Consumer<String> onStatus) {
        if (isActive()) {
            return false;
        }
        SttBackend backend = SttProviders.fromConfig(cfg);
        if (backend == null) {
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NOT_CONFIGURED));
            return false;
        }
        SttSession s = backend.open(new SttListener() {
            @Override
            public void onPartial(String text) {
                onMain(() -> onPartial.accept(text));
            }

            @Override
            public void onFinal(String text) {
                onMain(() -> {
                    active = false;
                    onFinal.accept(text);
                });
            }

            @Override
            public void onError(Throwable error) {
                onMain(() -> {
                    active = false;
                    onStatus.accept(I18n.get(ModLanguageData.Keys.STT_FAILED, rootMessage(error)));
                });
            }
        });
        session = s;
        if (!MicrophoneManager.start(cfg.getSttMicrophone(), s::feed,
                done(s, onStatus), noMic(s, onStatus))) {
            s.cancel();                       // 已经在录:这次请求不接,状态维持原样
            session = null;
            return false;
        }
        active = true;
        return true;
    }

    /**
     * 录完的收尾。
     *
     * <p>采到的全是零就<b>不送去识别</b>:那趟往返只会换回一个空串,而主人看到的是"识别失败",
     * 完全猜不到是权限、静音还是选错了设备。直接说"没采到声音"才是他能动手的信息。
     */
    private static Consumer<MicrophoneManager.Outcome> done(SttSession s, Consumer<String> onStatus) {
        return outcome -> {
            if (outcome == MicrophoneManager.Outcome.HEARD) {
                s.finish();
                return;
            }
            s.cancel();
            onMain(() -> {
                active = false;
                session = null;
                onStatus.accept(I18n.get(ModLanguageData.Keys.STT_SILENT));
            });
        };
    }

    /**
     * 设备开不了时的收尾。开设备在采集线程上做(渲染线程上做会卡一帧),所以这条是<b>异步</b>
     * 回来的——录音状态先乐观置上,失败到了再撤。
     */
    private static Runnable noMic(SttSession s, Consumer<String> onStatus) {
        return () -> onMain(() -> {
            active = false;
            session = null;
            s.cancel();
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NO_MIC));
        });
    }

    /** 对讲机式的松开:停采集,采集线程收尾时触发 session.finish() → onFinal。 */
    public static synchronized void stop() {
        if (isActive()) {
            MicrophoneManager.stop();
        }
    }

    /**
     * 切换录音。{@code onText} 收到(增量/最终)转写文本刷输入框;{@code onStatus}
     * 收到状态/错误提示(如未配置、无麦克风、请求失败)。
     */
    public static synchronized void toggle(INumenConfig cfg, Consumer<String> onText, Consumer<String> onStatus) {
        if (isActive()) {
            MicrophoneManager.stop();   // 采集线程收尾时回调 session.finish()
            active = false;
            return;
        }
        SttBackend backend = SttProviders.fromConfig(cfg);
        if (backend == null) {
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NOT_CONFIGURED));
            return;
        }
        SttSession s = backend.open(new SttListener() {
            @Override
            public void onPartial(String text) {
                onMain(() -> onText.accept(text));
            }

            @Override
            public void onFinal(String text) {
                onMain(() -> {
                    onText.accept(text);
                    active = false;
                });
            }

            @Override
            public void onError(Throwable error) {
                onMain(() -> {
                    onStatus.accept(I18n.get(ModLanguageData.Keys.STT_FAILED, rootMessage(error)));
                    active = false;
                });
            }
        });
        session = s;
        if (!MicrophoneManager.start(cfg.getSttMicrophone(), s::feed,
                done(s, onStatus), noMic(s, onStatus))) {
            s.cancel();                       // 已经在录:这次请求不接,状态维持原样
            session = null;
            return;
        }
        active = true;
    }

    private static void onMain(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(r);
        } else {
            r.run();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
