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
        boolean started = MicrophoneManager.start(cfg.getSttMicrophone(), s::feed, s::finish);
        if (!started) {
            s.cancel();
            active = false;
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
