package com.dwinovo.numen.api.brain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 她的记忆打成一个 zip 交给主人:对话日志({@code chat.jsonl})和札记({@code memory/} 下每条一个 {@code .md}),原样的文件。
 * 面板的"导出"用它,主人客户端把这些字节存成文件;服主看得到她的对话,主人也该能整份带走。
 */
public final class MemoryExport {

    /** 日志在她的家里叫什么。 */
    static final String CHAT = "chat.jsonl";
    /** 札记目录在她的家里叫什么。 */
    static final String MEMORY = "memory";

    private MemoryExport() {}

    /**
     * @param home 她的家 {@code companions/<uuid>/}
     * @return zip 的字节;日志和札记都没有时是一个空 zip
     */
    public static byte[] zip(Path home) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, CHAT, home.resolve(CHAT));
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
