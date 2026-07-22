package com.dwinovo.numen.client.stt;

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

    private static final AtomicBoolean RECORDING = new AtomicBoolean();
    private static volatile Thread thread;

    private MicrophoneManager() {}

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
     * 就回调 {@code onChunk}(采集线程上)。返回 false 表示无可用麦克风或已在录。
     */
    public static boolean start(String deviceName, Consumer<byte[]> onChunk, Runnable onStop) {
        if (!RECORDING.compareAndSet(false, true)) {
            return false;
        }
        TargetDataLine line = openLine(deviceName);
        if (line == null) {
            RECORDING.set(false);
            return false;
        }
        Thread t = new Thread(() -> capture(line, onChunk, onStop), "numen-stt-mic");
        t.setDaemon(true);
        thread = t;
        t.start();
        return true;
    }

    /** 停止采集(录音结束)。采集线程收尾后自然退出并回调 onStop。 */
    public static void stop() {
        RECORDING.set(false);
    }

    private static void capture(TargetDataLine line, Consumer<byte[]> onChunk, Runnable onStop) {
        long deadline = System.nanoTime() + MAX_RECORD_MS * 1_000_000L;
        byte[] buffer = new byte[CHUNK_BYTES];
        try {
            line.open(SttAudio.FORMAT);
            line.start();
            while (RECORDING.get() && System.nanoTime() < deadline) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    onChunk.accept(chunk);
                }
            }
        } catch (LineUnavailableException | RuntimeException ignored) {
            // fall through to cleanup + onStop
        } finally {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            RECORDING.set(false);
            thread = null;
            onStop.run();
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
            return null;
        }
        try {
            return (TargetDataLine) AudioSystem.getMixer(use).getLine(info);
        } catch (LineUnavailableException e) {
            return null;
        }
    }
}
