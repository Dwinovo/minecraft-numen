package com.dwinovo.numen.entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端的"谁在说话"名单:大脑在客户端,身体在服务端,身体想在主人说话/
 * 同伴回话期间做出"注视主人"这类姿态,得知道大脑此刻正在输出。客户端
 * 状态翻转时发 {@code SpeakingStatePayload} 过来,这里记账;闲时链读它,
 * 说话期间持续注视主人。纯姿态信号,丢包/漂移无害——最坏就是多看或
 * 少看几秒。
 */
public final class CompanionSpeech {

    private static final Map<UUID, Boolean> SPEAKING = new ConcurrentHashMap<>();

    private CompanionSpeech() {}

    public static void setSpeaking(UUID companionUuid, boolean speaking) {
        if (speaking) {
            SPEAKING.put(companionUuid, Boolean.TRUE);
        } else {
            SPEAKING.remove(companionUuid);
        }
    }

    public static boolean isSpeaking(UUID companionUuid) {
        return SPEAKING.getOrDefault(companionUuid, Boolean.FALSE);
    }
}
