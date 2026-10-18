package com.dwinovo.numen.api.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 这台电脑自己的文件住进 {@code client/}:旧的散放在根下的复制过去,原样留着,新的不被旧的盖掉。 */
class ClientFilesTest {

    private static void put(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theOldLooseFilesAreCopiedIntoTheClientDirectoryAndLeftWhereTheyWere(@TempDir Path dir) throws IOException {
        put(dir.resolve("ui.json"), "ui");
        put(dir.resolve("skin.json"), "skin");
        put(dir.resolve("skins/a.png"), "png");
        put(dir.resolve("voice.json"), "voice");
        put(dir.resolve("providers.json"), "不是这台电脑的");

        ClientFiles.migrate(dir, dir.resolve("client"));

        assertEquals("ui", Files.readString(dir.resolve("client/ui.json")));
        assertEquals("png", Files.readString(dir.resolve("client/skins/a.png")));
        assertEquals("voice", Files.readString(dir.resolve("client/voice.json")));
        assertTrue(Files.exists(dir.resolve("ui.json")), "旧的原样留着(单人游戏的服务端还读根下的)");
        assertFalse(Files.exists(dir.resolve("client/providers.json")), "服务器的东西不归客户端");
        assertFalse(Files.exists(dir.resolve("client/stt.json")), "没有旧的就不凭空建");
    }

    @Test
    void whatIsAlreadyInTheClientDirectoryIsNotOverwrittenAndDoingItTwiceChangesNothing(@TempDir Path dir)
            throws IOException {
        put(dir.resolve("ui.json"), "旧");
        put(dir.resolve("client/ui.json"), "新");

        ClientFiles.migrate(dir, dir.resolve("client"));
        ClientFiles.migrate(dir, dir.resolve("client"));

        assertEquals("新", Files.readString(dir.resolve("client/ui.json")));
    }
}
