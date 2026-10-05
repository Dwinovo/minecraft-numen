package net.minecraft.client.resources.language;

import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashMap;
import java.util.Map;

/**
 * 单测里的语言:原版的英文加上 Numen 自己的英文({@link ModLanguageData},语言键的唯一来源),装到 {@link I18n} 与
 * {@link Language} 上——和游戏里 {@code LanguageManager} 加载完语言时做的是同一件事(它也是经 {@code I18n.setLanguage}
 * 与 {@code Language.inject} 换上)。放在这个包里,只为够得着那个包内可见的 {@code setLanguage}。
 *
 * <p>没有它,单测里的 {@code I18n.get} 只会回语言键本身、参数全丢,给主人看的话说没说出该说的东西就测不到。
 */
public final class NumenTestLanguage {

    private static boolean installed;

    private NumenTestLanguage() {}

    /** 装上英文;一个进程只装一次。 */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        Map<String, String> numen = new HashMap<>();
        ModLanguageData.addTranslations("en_us", numen::put);
        Language vanilla = Language.getInstance();
        Language english = new Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                String own = numen.get(key);
                return own != null ? own : vanilla.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(String key) {
                return numen.containsKey(key) || vanilla.has(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return vanilla.getVisualOrder(text);
            }
        };
        I18n.setLanguage(english);
        Language.inject(english);
        installed = true;
    }
}
