package com.dwinovo.numen.client.chat;

/**
 * 记录里那条消息<b>原样</b>:{@code <query>} 外的一起画——{@code <known_blocks>}、
 * {@code <events>}、协议记号、模型的完整回复。
 *
 * <p>画的还是物理对话史,只是不做取舍。请求里临时拼、从不入记录的东西
 * ({@code <current_task>})这里也没有:它不在记录里,面板就不该画它。
 */
public final class RawMessageMode implements ChatDisplayMode {

    @Override
    public String userText(String raw) {
        return raw == null ? "" : raw;
    }

    @Override
    public String assistantText(String raw) {
        return raw == null ? "" : raw;
    }
}
