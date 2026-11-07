package com.dwinovo.numen.api.text;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NeoForge 许语言文件的译文写成文本组件(读组件要引导原版,所以和 {@link ServerLangTest} 分开)。 */
@Tag("mc")
class ServerLangComponentValueTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 写成文本组件的译文,表里是它的字面文字;同一个文件里的普通译文照旧。 */
    @Test
    void aTranslationWrittenAsATextComponentIsReadAsItsText(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("en_us.json");
        Files.writeString(file, "{\"somepack.warn\":[{\"text\":\"Read only\",\"color\":\"red\"}],"
                + "\"somepack.hello\":\"Hello\"}");
        ServerLang lang = ServerLang.read("en_us", List.of("somepack"), (mod, resource) -> file);

        assertEquals("Read only", lang.translate(Component.translatable("somepack.warn")).getString());
        assertEquals("Hello", lang.translate(Component.translatable("somepack.hello")).getString());
    }
}
