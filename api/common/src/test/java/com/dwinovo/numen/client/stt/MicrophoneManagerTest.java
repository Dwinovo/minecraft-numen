package com.dwinovo.numen.client.stt;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MicrophoneManagerTest {

    @Test
    void reportsMicrophoneOpenFailureBeforeRecordingStarts() {
        TargetDataLine unavailable = (TargetDataLine) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{TargetDataLine.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("open")) {
                        throw new LineUnavailableException("permission denied");
                    }
                    return defaultValue(method.getReturnType());
                });

        boolean started = MicrophoneManager.startLine(unavailable, ignored -> {}, () -> {});
        assertFalse(started);
        assertFalse(MicrophoneManager.isRecording());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
