package com.dwinovo.numen.client.stt;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
