package com.dwinovo.numen.agent.script;

import java.util.ArrayList;
import java.util.List;

/**
 * 回执的 stderr 一栏:API 与运行时主动报告的事,像命令行工具往 stderr 写——身体做了什么、没看全哪里、征询的结果,以及每一次 API 调用的
 * 失败(她用 {@code pcall} 接住了也写,同 Unix:失败的程序照样往 stderr 写,处不处理是调用方的事)。只返回值、没什么可报告的调用不写。
 * 每条带它在程序里的行号与函数名:{@code line 5 numen.fight.attack: killed minecraft:cow …}。
 *
 * <p>按构造有界({@link ScriptLimits}):一条至多 {@link ScriptLimits#STDERR_RECORD_CHARS} 字,超出的写明还有多少字没显示;连续相同的
 * 条合并成一条加 {@code (×N)};整栏至多 {@link ScriptLimits#STDERR_CHARS} 字,超出的条数与字数写明。纯 JVM。
 */
final class Stderr {

    /** 一条,以及它连续出现了几次。 */
    private static final class Record {
        final String text;
        int times = 1;

        Record(String text) {
            this.text = text;
        }
    }

    private final List<Record> records = new ArrayList<>();

    /**
     * 记一条。
     *
     * @param line     程序里的行号(经模块函数调到的,是程序里调那个函数的那一行)
     * @param function 哪个函数
     * @param words    报告的话;空白就什么也不记。多行的,第二行起缩进两格,读得出还是这一条
     */
    void write(int line, String function, String words) {
        String said = words == null ? "" : words.strip();
        if (said.isEmpty()) {
            return;
        }
        String text = "line " + line + " " + function + ": " + bounded(said).replace("\n", "\n  ");
        Record last = records.isEmpty() ? null : records.getLast();
        if (last != null && last.text.equals(text)) {
            last.times++;
        } else {
            records.add(new Record(text));
        }
    }

    boolean isEmpty() {
        return records.isEmpty();
    }

    /** 整栏的文字,一条一行(多行的条占几行);没有记过是空串。超出总预算的条省略,末尾写明。 */
    String text() {
        StringBuilder out = new StringBuilder();
        int left = 0;
        int leftChars = 0;
        for (Record r : records) {
            String shown = r.times > 1 ? r.text + " (×" + r.times + ")" : r.text;
            if (left > 0 || out.length() + shown.length() + 1 > ScriptLimits.STDERR_CHARS) {
                left++;
                leftChars += shown.length() + 1;
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(shown);
        }
        if (left > 0) {
            out.append("\n[").append(left).append(" more stderr entr").append(left == 1 ? "y" : "ies").append(" (")
                    .append(leftChars).append(" characters) left out: stderr keeps the first ")
                    .append(ScriptLimits.STDERR_CHARS).append(" characters]");
        }
        return out.toString();
    }

    /** 一条留下 {@link ScriptLimits#STDERR_RECORD_CHARS} 以内:整行地留(第一行总留,放不下就截),其余的字数写出来。 */
    private static String bounded(String text) {
        int limit = ScriptLimits.STDERR_RECORD_CHARS;
        if (text.length() <= limit) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        int next = 0;
        while (next < lines.length && (next == 0 || kept.length() + 1 + lines[next].length() <= limit)) {
            if (next > 0) {
                kept.append('\n');
            }
            kept.append(lines[next++]);
        }
        if (kept.length() > limit) {
            kept.setLength(limit);
        }
        return kept + "\n[" + (text.length() - kept.length()) + " more characters of this entry left out]";
    }
}
