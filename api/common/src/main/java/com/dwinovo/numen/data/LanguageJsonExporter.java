package com.dwinovo.numen.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes the shared translation catalogue without starting Minecraft. */
public final class LanguageJsonExporter {

    private LanguageJsonExporter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: LanguageJsonExporter <output-root> <namespace>");
        }
        Map<String, String> en = translations("en_us");
        Map<String, String> zh = translations("zh_cn");
        if (!en.keySet().equals(zh.keySet())) {
            throw new IllegalStateException("English and Chinese translation keys differ");
        }
        Path langDir = Path.of(args[0], "assets", args[1], "lang");
        Files.createDirectories(langDir);
        write(langDir.resolve("en_us.json"), en);
        write(langDir.resolve("zh_cn.json"), zh);
    }

    public static Map<String, String> translations(String locale) {
        Map<String, String> values = new LinkedHashMap<>();
        ModLanguageData.addTranslations(locale, (key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank() || value.equals(key)) {
                throw new IllegalArgumentException("Translation keys and values must contain actual text");
            }
            String previous = values.putIfAbsent(key, value);
            if (previous != null) {
                throw new IllegalStateException("Duplicate translation key: " + key);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static void write(Path output, Map<String, String> values) throws IOException {
        StringBuilder json = new StringBuilder(values.size() * 48).append("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            json.append("  \"").append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append('"');
            if (++index < values.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("}\n");
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
