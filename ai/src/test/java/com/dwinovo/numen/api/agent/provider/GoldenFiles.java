package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 读 {@code src/test/resources/golden} 下的黄金文件。 */
final class GoldenFiles {

    private GoldenFiles() {}

    static String text(String path) {
        try (var in = GoldenFiles.class.getResourceAsStream("/golden/" + path)) {
            if (in == null) throw new IllegalStateException("missing golden file " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一个 JSON 文件里按名字取一个对象。 */
    static JsonObject object(String file, String name) {
        return JsonParser.parseString(text(file)).getAsJsonObject().getAsJsonObject(name);
    }

    /** SSE 文本里每个 {@code data:} 行的 JSON(忽略 event 行与 [DONE])。 */
    static List<JsonObject> sseChunks(String path) {
        List<JsonObject> out = new ArrayList<>();
        for (String line : text(path).split("\\R")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if (data.isEmpty() || data.equals("[DONE]")) continue;
            out.add(JsonParser.parseString(data).getAsJsonObject());
        }
        return out;
    }
}
