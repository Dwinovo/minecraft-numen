package com.dwinovo.numen.api.gametest;

import com.mojang.serialization.DataResult;
import java.util.Locale;
import net.kyori.adventure.translation.Translator;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * 客户端真正看到的文字。Paper 把可翻译的组件在<b>编码成包的那一刻</b>按玩家的语言渲染({@code PacketEncoder} 把玩家的语言带进缓冲,
 * 序列化走 {@link ComponentSerialization#localizedCodec},里头用的是 Adventure 的全局翻译源,Numen 的语言表就登记在那儿)。
 * 模拟连接不编码,所以这里走同一个本地化编码器编一遍、再按线上的格式解回来:得到的就是客户端收到的组件,里面的翻译键已经是字。
 */
final class PaperClientText {

    private PaperClientText() {}

    /** {@code text} 在语言为 {@code language}(客户端报上来的写法,如 {@code zh_cn})的客户端上的样子。 */
    static Component rendered(Component text, String language) {
        Locale locale = Translator.parseLocale(language);
        DataResult<Tag> sent = ComponentSerialization.localizedCodec(locale).encodeStart(NbtOps.INSTANCE, text);
        return ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, sent.getOrThrow()).getOrThrow();
    }
}
