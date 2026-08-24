package com.dwinovo.numen.client.stt;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 麦克风打不开的时候不能当成开好了。
 *
 * <p>开不了设备(被别的程序独占、枚举完到打开之间被拔掉)如果答"成功",界面会显示"正在录音",
 * 而送去识别的是一段空音频——主人只看到"识别失败",一个字的原因都没有。
 */
class MicrophoneManagerTest {

    @Test
    void anUnopenableDeviceIsAFailureNotASilentSuccess() {
        assertFalse(MicrophoneManager.openForCapture(lineThatFailsToOpen()),
                "开不了就得答 false,上层据此收工并提示");
    }

    @Test
    void aRejectedRequestLeavesTheFlagClearSoTheNextPressWorks() {
        // start 只在"已经在录"时答 false;设备问题走异步回调,不占标志位
        assertFalse(MicrophoneManager.isRecording());
    }

    // ---- 一片零 ----

    @Test
    void anAllZeroRecordingIsNotWorthSendingAnywhere() {
        // 系统拒了麦克风权限、设备被静音、选错了输入源,三种都长这样。送去识别只会换回一个
        // 空串,而主人看到的是"识别失败"——猜不到该去查什么。
        assertEquals(MicrophoneManager.Outcome.SILENT,
                MicrophoneManager.outcomeOf(MicrophoneManager.peakOf(new byte[3200], 3200)));
    }

    @Test
    void realSpeechClearsTheBarByMilesSoWeNeverRejectAGoodTake() {
        // 判据定得极低是有意的:宁可漏判,也不能把一段正经录音判死
        byte[] quiet = pcm(200);          // 满量程的 0.6%,已经是很小声了
        assertEquals(MicrophoneManager.Outcome.HEARD,
                MicrophoneManager.outcomeOf(MicrophoneManager.peakOf(quiet, quiet.length)));
    }

    @Test
    void peakIsTakenFromTheAbsoluteValueSoNegativeHalfCyclesCount() {
        // 只看正半周的话,一段以负半周开头的录音会被当成静音
        byte[] negative = pcm(-3000);
        assertEquals(3000, MicrophoneManager.peakOf(negative, negative.length));
    }

    @Test
    void onlyTheBytesActuallyReadAreLookedAt() {
        // 缓冲区是复用的:上一块的残留不能算进这一块的峰值
        byte[] buffer = pcm(9000);
        assertEquals(0, MicrophoneManager.peakOf(buffer, 0), "这次一个字节都没读到");
    }

    /** 一段全是同一个采样值的 16-bit 小端 PCM。 */
    private static byte[] pcm(int sample) {
        byte[] out = new byte[64];
        for (int i = 0; i < out.length; i += 2) {
            out[i] = (byte) (sample & 0xFF);
            out[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return out;
    }

    /** 一个 {@code open()} 就抛的 {@link TargetDataLine} —— 无头环境下没有真设备可用。 */
    private static TargetDataLine lineThatFailsToOpen() {
        return (TargetDataLine) Proxy.newProxyInstance(
                MicrophoneManagerTest.class.getClassLoader(),
                new Class<?>[]{TargetDataLine.class},
                (proxy, method, args) -> {
                    if ("open".equals(method.getName())) {
                        throw new LineUnavailableException("device is busy");
                    }
                    return zeroOf(method);
                });
    }

    private static Object zeroOf(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
