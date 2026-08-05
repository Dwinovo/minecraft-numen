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
    private static volatile boolean starting;
    private static volatile boolean backendOwnsMicrophone;

    private VoiceInputController() {}

    public static boolean isActive() {
        return active || MicrophoneManager.isRecording();
    }

    private static boolean isBusy() {
        return active || starting || MicrophoneManager.isRecording();
    }

    /**
     * 对讲机式:按下开录,{@link #stop()} 松开收音。与 {@link #toggle} 的差别
     * 只在回调口径——增量与最终分开,方便"松开后拿最终转写直接发送"的用法。
     * 已在录音中则直接返回 false(两条路共用一只麦克风,不并录)。
     */
    public static synchronized boolean start(INumenConfig cfg, Consumer<String> onPartial,
                                             Consumer<String> onFinal, Consumer<String> onStatus) {
        if (isBusy()) {
            return false;
        }
        SttBackend backend = SttProviders.fromConfig(cfg);
        if (backend == null) {
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NOT_CONFIGURED));
            return false;
        }
        boolean bridgeCapture = backend.capturesMicrophone();
        starting = bridgeCapture;
        backendOwnsMicrophone = bridgeCapture;
        SttSession s = backend.open(new SttListener() {
            @Override
            public void onCaptureStarted() {
                onMain(() -> {
                    starting = false;
                    active = true;
                });
            }

            @Override
            public void onPartial(String text) {
                onMain(() -> onPartial.accept(text));
            }

            @Override
            public void onFinal(String text) {
                onMain(() -> {
                    active = false;
                    starting = false;
                    backendOwnsMicrophone = false;
                    session = null;
                    onFinal.accept(text);
                });
            }

            @Override
            public void onError(Throwable error) {
                onMain(() -> {
                    active = false;
                    starting = false;
                    backendOwnsMicrophone = false;
                    session = null;
                    onStatus.accept(I18n.get(ModLanguageData.Keys.STT_FAILED, rootMessage(error)));
                });
            }
        });
        session = s;
        if (bridgeCapture) {
            return true;
        }
        boolean started = MicrophoneManager.start(cfg.getSttMicrophone(), s::feed, s::finish);
        if (!started) {
            s.cancel();
            active = false;
            starting = false;
            backendOwnsMicrophone = false;
            session = null;
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NO_MIC));
            return false;
        }
        active = true;
        return true;
    }

    /** 对讲机式的松开:停采集,采集线程收尾时触发 session.finish() → onFinal。 */
    public static synchronized void stop() {
        if (isBusy()) {
            if (backendOwnsMicrophone) {
                SttSession s = session;
                session = null;
                starting = false;
                active = false;
                backendOwnsMicrophone = false;
                if (s != null) s.finish();
                return;
            }
            MicrophoneManager.stop();
        }
    }

    /**
     * 切换录音。{@code onText} 收到(增量/最终)转写文本刷输入框;{@code onStatus}
     * 收到状态/错误提示(如未配置、无麦克风、请求失败)。
     */
    public static synchronized void toggle(INumenConfig cfg, Consumer<String> onText, Consumer<String> onStatus) {
        if (isBusy()) {
            if (backendOwnsMicrophone) {
                SttSession s = session;
                session = null;
                starting = false;
                active = false;
                backendOwnsMicrophone = false;
                if (s != null) s.finish();
            } else {
                MicrophoneManager.stop();   // 采集线程收尾时回调 session.finish()
            }
            active = false;
            return;
        }
        SttBackend backend = SttProviders.fromConfig(cfg);
        if (backend == null) {
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NOT_CONFIGURED));
            return;
        }
        boolean bridgeCapture = backend.capturesMicrophone();
        starting = bridgeCapture;
        backendOwnsMicrophone = bridgeCapture;
        SttSession s = backend.open(new SttListener() {
            @Override
            public void onCaptureStarted() {
                onMain(() -> {
                    starting = false;
                    active = true;
                });
            }

            @Override
            public void onPartial(String text) {
                onMain(() -> onText.accept(text));
            }

            @Override
            public void onFinal(String text) {
                onMain(() -> {
                    onText.accept(text);
                    active = false;
                    starting = false;
                    backendOwnsMicrophone = false;
                    session = null;
                });
            }

            @Override
            public void onError(Throwable error) {
                onMain(() -> {
                    onStatus.accept(I18n.get(ModLanguageData.Keys.STT_FAILED, rootMessage(error)));
                    active = false;
                    starting = false;
                    backendOwnsMicrophone = false;
                    session = null;
                });
            }
        });
        session = s;
        if (bridgeCapture) {
            return;
        }
        boolean started = MicrophoneManager.start(cfg.getSttMicrophone(), s::feed, s::finish);
        if (!started) {
            s.cancel();
            active = false;
            starting = false;
            backendOwnsMicrophone = false;
            session = null;
            onStatus.accept(I18n.get(ModLanguageData.Keys.STT_NO_MIC));
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
