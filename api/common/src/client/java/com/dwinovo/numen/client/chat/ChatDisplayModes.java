package com.dwinovo.numen.client.chat;

/** 当前生效的 {@link ChatDisplayMode}(客户端单例,可整体替换)。 */
public final class ChatDisplayModes {

    private static ChatDisplayMode current = new OwnerWordsMode();

    private ChatDisplayModes() {}

    public static ChatDisplayMode current() {
        return current;
    }

    /** 换一份来看(传 null 回落到看对话)。 */
    public static void set(ChatDisplayMode mode) {
        current = mode == null ? new OwnerWordsMode() : mode;
    }
}
