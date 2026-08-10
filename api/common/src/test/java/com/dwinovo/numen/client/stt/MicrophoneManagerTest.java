package com.dwinovo.numen.client.stt;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 麦克风打不开的时候得当场说不。
 *
 * <p>{@code open()} 曾经在采集线程上做:{@code start()} 早已答了"在录"、界面也已经显示在录,
 * 失败只会变成一段空音频送去识别,主人看到的是"识别失败",一个字的原因都没有。设备被别的
 * 程序独占、枚举完到打开之间被拔掉,都走这条路。
 */
class MicrophoneManagerTest {

    @Test
    void anUnopenableDeviceIsReportedBeforeRecordingIsClaimed() {
        boolean started = MicrophoneManager.startLine(lineThatFailsToOpen(), chunk -> {}, () -> {});

        assertFalse(started, "开不了就得答 false,上层才提示得出来");
        assertFalse(MicrophoneManager.isRecording(), "标志位不能卡在'在录',否则之后再也开不了");
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
