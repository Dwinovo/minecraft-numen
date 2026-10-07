package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.Constants;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 客户端麦克风采集:纯 JDK {@code javax.sound.sampled},无 native 依赖、跨平台。
 * 采集格式固定 {@link SttAudio#FORMAT}(16kHz 单声道 PCM),边采边把 PCM 块喂给
 * 消费者(供批量会话缓冲 / 流式会话实时发)。一次只跑一路采集。
 */
public final class MicrophoneManager {

    /** 硬上限,防按住不放/异常导致无限录音。 */
    private static final int MAX_RECORD_MS = 60_000;
    private static final int CHUNK_BYTES = 3200;   // 100ms @ 16kHz/16-bit/mono

    /**
     * 整段录音的峰值不超过这个数(满量程 32767)就当没采到声音。
     *
     * <p>定得极低是有意的:这道判据只为逮"一个采样点都不动"那种——系统拒了麦克风权限、
     * 设备被静音、选错了输入源,三种都是一片零。真人说话哪怕再小声也远在这之上,宁可漏判
     * 也不能把一段正经录音判死。
     */
    private static final int SILENCE_PEAK = 8;

    private static final AtomicBoolean RECORDING = new AtomicBoolean();
    private static volatile Thread thread;

    private MicrophoneManager() {}

    /**
     * 一次采集的结局。
     *
     * <p>{@link #SILENT} 不是失败——设备开了、也读到数据了,只是里面什么都没有。这跟"打不开
     * 设备"是两回事,给主人的话也不一样,所以分开报:一个让他查权限/静音,一个让他查设备。
     */
    public enum Outcome {
        /** 采到了声音,可以送去识别。 */
        HEARD,
        /** 全程一片零。送去识别只会换回一个空串,不如直接说清楚。 */
        SILENT
    }

    public static boolean isRecording() {
        return RECORDING.get();
    }

    /** 支持所需格式的输入设备名列表(供设置页下拉)。 */
    public static List<String> deviceNames() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, SttAudio.FORMAT);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mi).isLineSupported(info)) {
                names.add(mi.getName());
            }
        }
        return names;
    }

    /**
     * 开始采集。{@code deviceName} 为空/找不到则用第一个可用设备。每采到一块 PCM
     * 就回调 {@code onChunk}(采集线程上);录完回调 {@code onDone} 并告诉它这段里
     * 到底有没有声音;开不了设备时回调 {@code onFailed}。
     *
     * <h2>为什么失败走回调而不是返回值</h2>
     * 设备枚举({@link AudioSystem#getMixerInfo})和 {@code line.open()} 在 Windows 上都要几十到
     * 上百毫秒。这个方法是按下说话键时在<b>渲染线程</b>上调的,同步做完这两件事就是肉眼可见的
     * 一顿。所以两件都挪进采集线程,失败异步报——上层照样收得到提示,只是晚一帧。
     *
     * @return 是否接下了这次请求;{@code false} 只意味着已经在录,不代表设备有问题
     */
    public static boolean start(String deviceName, Consumer<byte[]> onChunk,
                                Consumer<Outcome> onDone, Runnable onFailed) {
        if (!RECORDING.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            TargetDataLine line = openLine(deviceName);
            if (line == null || !openForCapture(line)) {
                RECORDING.set(false);
                thread = null;
                onFailed.run();
                return;
            }
            capture(line, onChunk, onDone);
        }, "numen-stt-mic");
        t.setDaemon(true);
        thread = t;
        t.start();
        return true;
    }

    /**
     * 打开并启动一条输入线路。
     *
     * <p>开不了就是开不了(设备被别的程序独占、枚举完到打开之间被拔掉),必须答 {@code false}
     * 让上层收工——不然界面显示"正在录音",实际送去识别的是一段空音频,主人只看到"识别失败"。
     */
    static boolean openForCapture(TargetDataLine line) {
        try {
            line.open(SttAudio.FORMAT);
            line.start();
            return true;
        } catch (LineUnavailableException | RuntimeException e) {
            Constants.LOG.warn("[numen-stt] 打不开麦克风", e);
            closeQuietly(line);
            return false;
        }
    }

    /** 停止采集(录音结束)。采集线程收尾后自然退出并回调 onDone。 */
    public static void stop() {
        RECORDING.set(false);
    }

    /** 一块 PCM 里的最大绝对振幅(16-bit 小端)。 */
    static int peakOf(byte[] pcm, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    /** 整段录音的峰值够不够得上"有人说话"。 */
    static Outcome outcomeOf(int peak) {
        return peak > SILENCE_PEAK ? Outcome.HEARD : Outcome.SILENT;
    }

    private static void capture(TargetDataLine line, Consumer<byte[]> onChunk, Consumer<Outcome> onDone) {
        long deadline = System.nanoTime() + MAX_RECORD_MS * 1_000_000L;
        byte[] buffer = new byte[CHUNK_BYTES];
        int peak = 0;
        try {
            while (RECORDING.get() && System.nanoTime() < deadline) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) {
                    peak = Math.max(peak, peakOf(buffer, read));
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    onChunk.accept(chunk);
                }
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn("[numen-stt] 采集中断", e);
        } finally {
            closeQuietly(line);
            RECORDING.set(false);
            thread = null;
            Outcome outcome = outcomeOf(peak);
            if (outcome == Outcome.SILENT) {
                // 一片零最常见的三个来源:系统拒了麦克风权限、设备被静音、选错了输入源。
                // 三种在这一层分不开,但至少要说出"没采到声音",别让它变成一句"识别失败"。
                Constants.LOG.warn("[numen-stt] 这段录音一点声音都没有(峰值 {}),没送去识别", peak);
            }
            onDone.accept(outcome);
        }
    }

    private static void closeQuietly(TargetDataLine line) {
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
    private static TargetDataLine openLine(String deviceName) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, SttAudio.FORMAT);
        Mixer.Info chosen = null;
        Mixer.Info first = null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!AudioSystem.getMixer(mi).isLineSupported(info)) {
                continue;
            }
            if (first == null) {
                first = mi;
            }
            if (deviceName != null && !deviceName.isBlank() && mi.getName().equals(deviceName)) {
                chosen = mi;
                break;
            }
        }
        Mixer.Info use = chosen != null ? chosen : first;
        if (use == null) {
            // 一台机器上没有能出 16kHz 单声道 PCM 的输入设备。这条以前是静默返回,
            // 界面只说"没有可用麦克风",日志里查不到任何线索。
            Constants.LOG.warn("[numen-stt] 没有支持 {} 的输入设备;系统里的混音器: {}",
                    SttAudio.FORMAT, deviceNames());
            return null;
        }
        if (chosen == null && deviceName != null && !deviceName.isBlank()) {
            Constants.LOG.warn("[numen-stt] 设置里选的麦克风 '{}' 不在了,改用 '{}'",
                    deviceName, use.getName());
        }
        try {
            return (TargetDataLine) AudioSystem.getMixer(use).getLine(info);
        } catch (LineUnavailableException e) {
            Constants.LOG.warn("[numen-stt] 取不到输入线路 '{}'", use.getName(), e);
            return null;
        }
    }
}
