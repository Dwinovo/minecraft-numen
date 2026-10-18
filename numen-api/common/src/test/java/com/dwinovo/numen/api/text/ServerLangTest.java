package com.dwinovo.numen.api.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLangTest {

    private static final ServerLang LANG = new ServerLang(Map.of(
            "numen.plain", "要等一下",
            "numen.one", "拿 %s 个",
            "numen.two", "%2$s 在 %1$s",
            "numen.pct", "100%% 完成,还剩 %s",
            "numen.nested", "她说:%s",
            "numen.broken", "坏的 %d"));

    @Test
    void aNumenKeyBecomesItsTranslationWithTheArgumentsFilledIn() {
        assertEquals("拿 3 个", LANG.translate(Component.translatable("numen.one", 3)).getString());
        assertEquals("矿洞 在 北边", LANG.translate(Component.translatable("numen.two", "北边", "矿洞")).getString());
        assertEquals("100% 完成,还剩 2", LANG.translate(Component.translatable("numen.pct", 2)).getString());
    }

    @Test
    void placeholdersBecomeMessageFormatSlotsAndQuotesAreEscaped() {
        assertEquals("拿 {0} 个", LANG.asMessageFormat("numen.one"));
        assertEquals("{1} 在 {0}", LANG.asMessageFormat("numen.two"));
        assertEquals("100% 完成,还剩 {0}", LANG.asMessageFormat("numen.pct"));
        ServerLang tricky = new ServerLang(Map.of("numen.q", "it's {braced} %s"));
        assertEquals("it''s '{'braced'}' {0}", tricky.asMessageFormat("numen.q"));
        assertEquals("it's {braced} 5",
                new java.text.MessageFormat(tricky.asMessageFormat("numen.q")).format(new Object[] {5}));
    }

    @Test
    void aKeyWithoutATranslationOrWithAnUnknownPlaceholderHasNoMessageFormat() {
        assertEquals(null, LANG.asMessageFormat("numen.missing"));
        assertEquals(null, LANG.asMessageFormat("numen.broken"));
    }

    @Test
    void aKeyThatIsNotNumensIsLeftForTheClientToTranslate() {
        Component vanilla = Component.translatable("block.minecraft.stone");
        assertSame(vanilla, LANG.translate(vanilla));
        Component literal = Component.literal("<她> 好的");
        assertSame(literal, LANG.translate(literal));
    }

    @Test
    void numenKeysInsideAVanillaKeyAreStillTranslated() {
        Component whisper = Component.translatable("commands.message.display.incoming", "小柚",
                Component.translatable("numen.plain"));
        Component translated = LANG.translate(whisper);
        TranslatableContents contents = (TranslatableContents) translated.getContents();
        assertEquals("commands.message.display.incoming", contents.getKey());
        assertEquals("要等一下", ((Component) contents.getArgs()[1]).getString());
    }

    @Test
    void anArgumentThatIsItselfTranslatableIsTranslatedToo() {
        Component nested = Component.translatable("numen.nested", Component.translatable("numen.plain"));
        assertEquals("她说:要等一下", LANG.translate(nested).getString());
    }

    @Test
    void theStyleAndTheTextAfterItSurvive() {
        MutableComponent clickable = Component.translatable("numen.plain").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" ")).append(Component.translatable("numen.one", 1));
        Component translated = LANG.translate(clickable);
        assertEquals("要等一下 拿 1 个", translated.getString());
        assertEquals(ChatFormatting.GREEN.getColor().intValue(), translated.getStyle().getColor().getValue());
    }

    @Test
    void aTranslationWithABadPlaceholderStaysAsItWas() {
        Component broken = Component.translatable("numen.broken", 1);
        assertSame(broken, LANG.translate(broken));
        assertTrue(LANG.has("numen.broken"));
    }

    /** 不论模组叫什么,它的语言文件都读进表(英文打底、他的语言盖上);原版命名空间那份不读,原版的键原样留着。 */
    @Test
    void everyLoadedModsKeysAreTranslatedWhateverItIsCalledAndVanillaIsLeftAlone(@TempDir Path dir) throws IOException {
        Map<String, String> files = Map.of(
                "somepack/en_us", "{\"somepack.hello\":\"Hello\",\"somepack.only_en\":\"English only\"}",
                "somepack/zh_cn", "{\"somepack.hello\":\"你好\"}",
                "minecraft/en_us", "{\"block.minecraft.stone\":\"Stone\"}");
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = dir.resolve(file.getKey().replace('/', '_'));
            Files.writeString(path, file.getValue());
        }
        ServerLang lang = ServerLang.read("zh_cn", List.of("minecraft", "somepack", "absent"), (mod, resource) -> {
            Path path = dir.resolve(mod + "_" + resource.substring(resource.lastIndexOf('/') + 1, resource.length() - 5));
            return Files.exists(path) ? path : null;
        });

        assertEquals("你好", lang.translate(Component.translatable("somepack.hello")).getString());
        assertEquals("English only", lang.translate(Component.translatable("somepack.only_en")).getString());
        Component stone = Component.translatable("block.minecraft.stone");
        assertSame(stone, lang.translate(stone));
    }
}
