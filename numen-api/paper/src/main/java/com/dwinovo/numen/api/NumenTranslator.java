package com.dwinovo.numen.api;

import com.dwinovo.numen.api.text.ServerLang;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.Translator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * 给没装 Numen 客户端的玩家翻译:原版客户端不认识 {@code numen.*} 的键,收到带键的消息只会显示键本身。模组上由出口包改写,
 * Paper 把可翻译组件编码给每位玩家时按他的语言过 Adventure 的翻译源({@code GlobalTranslator}),所以注册一个:
 * 语言表就是 {@link ServerLang} 那一份(各插件 jar 里的 {@code assets/<id>/lang/<语言>.json}),占位符改写成 MessageFormat 的写法
 * 由 Adventure 填参数,样式与子组件它自己保留。语言表没有的键返回 {@code null},交给别的翻译源(原版的键它们认识)。
 */
final class NumenTranslator implements Translator {

    @Override
    public @NotNull Key name() {
        return Key.key(Constants.MOD_ID, "server_lang");
    }

    @Override
    public @Nullable MessageFormat translate(@NotNull String key, @NotNull Locale locale) {
        String pattern = ServerLang.of(locale.toString()).asMessageFormat(key);
        return pattern == null ? null : new MessageFormat(pattern, locale);
    }
}
