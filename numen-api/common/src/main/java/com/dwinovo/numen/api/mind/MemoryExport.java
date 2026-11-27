package com.dwinovo.numen.api.mind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 她的记忆打成一个 zip 交给主人:当前的对话记录({@code conversation.jsonl},向服务端要来的原文)和札记({@code memory/} 下每条一个 {@code .md}),
 * 原样的文件。面板的"导出"用它,主人客户端把这些字节存成文件;服主看得到她的对话,主人也该能整份带走。
 */
public final class MemoryExport {

    /** 对话记录在 zip 里叫什么。 */
    static final String CONVERSATION = "conversation.jsonl";
    /** 札记目录在她的家里叫什么。 */
    static final String MEMORY = "memory";

    private MemoryExport() {}

    /**
     * @param home         她的家 {@code companions/<uuid>/}
     * @param conversation 当前对话记录的原文;空串是没有
     * @return zip 的字节;对话记录和札记都没有时是一个空 zip
     */
    public static byte[] zip(Path home, String conversation) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (!conversation.isEmpty()) {
                zip.putNextEntry(new ZipEntry(CONVERSATION));
                zip.write(conversation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Path notes = home.resolve(MEMORY);
            if (Files.isDirectory(notes)) {
                List<Path> files;
                try (Stream<Path> list = Files.list(notes)) {
                    files = list.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : files) {
                    add(zip, MEMORY + "/" + file.getFileName(), file);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
