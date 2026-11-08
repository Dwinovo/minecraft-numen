package com.dwinovo.numen.api.client.voice;

import net.minecraft.client.resources.language.I18n;

/**
 * 语音失败({@link VoiceError})翻成当前语言的话,界面里说"语音为什么没成"的唯一一处:设置里的试听、
 * 语音输入的状态行都用它。文案只在语言文件里({@code numen.voice.error.<码>}),这里只按码挑键、把原话填进去;
 * 有厂家原话的接在后面({@code numen.voice.error.detail})。
 */
public final class VoiceWords {

    private VoiceWords() {}

    public static String text(Throwable error) {
        VoiceError e = VoiceError.fromError(error);
        String base = I18n.get("numen.voice.error." + e.code(), e.arg(0), e.arg(1));
        String detail = switch (e.code()) {
            case VoiceError.HTTP -> e.arg(2);
            case VoiceError.API -> e.arg(1);
            case VoiceError.CLOSED -> e.arg(1);
            default -> "";
        };
        return detail.isBlank() ? base : I18n.get("numen.voice.error.detail", base, detail);
    }
}
