package com.dwinovo.numen.bench;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 一次运行的记录,一行一件事:主人说的话、她说的话、每个工具调用(她写的程序)与它的回执、进她收件箱的世界事件(前 {@link #CLIP} 字)、
 * 征询与答复、收场。<b>不含模型的思考</b>:思考流评测从不读、也不落。
 */
final class Transcript {

    /** 工具结果与事件只留这么多字。 */
    static final int CLIP = 200;

    private final Path file;
    private final long startMs = System.currentTimeMillis();

    Transcript(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path file() {
        return file;
    }

    void write(String kind, String... keyValues) {
        JsonObject line = new JsonObject();
        line.addProperty("ms", System.currentTimeMillis() - startMs);
        line.addProperty("kind", kind);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            line.addProperty(keyValues[i], keyValues[i + 1]);
        }
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= CLIP ? s : s.substring(0, CLIP) + "…";
    }
}
