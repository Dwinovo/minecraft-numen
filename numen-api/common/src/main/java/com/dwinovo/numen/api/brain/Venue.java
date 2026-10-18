package com.dwinovo.numen.api.brain;

/**
 * 主人是在哪儿跟她说的这句话。她的回话在哪问就在哪答({@link Voice} 按它分发):
 * 一句话问的是哪里,她接下来说出口的话就回到哪里,直到主人换个地方再开口。会话态,不落盘。
 */
public enum Venue {

    /** 面板、快捷对话、语音转写,以及渠道送来的输入:回在面板;没装客户端的主人只能得到一行聊天。 */
    PANEL,

    /** {@code /numen chat} 切进(或指明)的会话:回在带 {@code [会话名]} 的聊天行。 */
    CHAT,

    /** 原版 {@code /msg}(与 {@code /tell}、{@code /w}):回私信。 */
    WHISPER
}
