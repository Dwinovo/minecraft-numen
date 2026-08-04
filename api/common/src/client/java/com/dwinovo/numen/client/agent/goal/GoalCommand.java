package com.dwinovo.numen.client.agent.goal;

/** The supported {@code /goal} command verbs. */
public enum GoalCommand {
    STATUS("status", "查看当前 goal 状态"),
    HELP("help", "显示可用 goal 指令"),
    LIST("list", "列出当前 goal"),
    ADD("add", "/goal add <内容> 创建新 goal", true),
    UPDATE("update", "/goal update <内容> 更新当前 goal 标题", true),
    PROGRESS("progress", "查看 goal 进度"),
    COMPLETE("complete", "标记当前 goal 完成"),
    CANCEL("cancel", "取消当前 goal"),
    PAUSE("pause", "暂停当前 goal"),
    BLOCKED("blocked", "标记当前 goal 被阻塞", true),
    RESUME("resume", "恢复当前 goal"),
    RECENT("recent", "查看最近 goal 操作"),
    COMPACT("compact", "请求压缩上下文"),
    SETTINGS("settings", "查看 goal 设置");

    private final String text;
    private final String help;
    private final boolean requiresArgument;

    GoalCommand(String text, String help) {
        this(text, help, false);
    }

    GoalCommand(String text, String help, boolean requiresArgument) {
        this.text = text;
        this.help = help;
        this.requiresArgument = requiresArgument;
    }

    public String text() {
        return text;
    }

    public String help() {
        return help;
    }

    public boolean requiresArgument() {
        return requiresArgument;
    }

    public static GoalCommand parse(String verb) {
        if (verb == null) return null;
        for (GoalCommand command : values()) {
            if (command.text.equalsIgnoreCase(verb)) return command;
        }
        return null;
    }

    /**
     * Return a high-confidence correction for a mistyped command verb. Direct
     * goal text is intentionally permissive, so this avoids broad fuzzy
     * matching that would turn ordinary words into command errors.
     */
    static GoalCommand typoSuggestion(String verb) {
        if (verb == null) return null;
        String candidate = verb.toLowerCase(java.util.Locale.ROOT);
        if (!asciiLetters(candidate)) return null;
        for (GoalCommand command : values()) {
            String expected = command.text;
            if (candidate.equals(expected)) continue;
            if (isAdjacentTransposition(candidate, expected)
                    || isTrailingExtraCharacter(candidate, expected)
                    || isLongCommandSingleEdit(candidate, expected)) {
                return command;
            }
        }
        return null;
    }

    private static boolean isLongCommandSingleEdit(String candidate, String expected) {
        return expected.length() >= 5
                && !candidate.isEmpty()
                && candidate.charAt(0) == expected.charAt(0)
                && isSingleEdit(candidate, expected);
    }

    private static boolean isAdjacentTransposition(String candidate, String expected) {
        if (candidate.length() != expected.length() || candidate.length() < 3) return false;
        int first = -1;
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) == expected.charAt(i)) continue;
            if (first < 0) {
                first = i;
                continue;
            }
            return i == first + 1
                    && candidate.charAt(first) == expected.charAt(i)
                    && candidate.charAt(i) == expected.charAt(first)
                    && candidate.regionMatches(i + 1, expected, i + 1,
                            candidate.length() - i - 1);
        }
        return false;
    }

    private static boolean isTrailingExtraCharacter(String candidate, String expected) {
        return candidate.length() == expected.length() + 1 && candidate.startsWith(expected);
    }

    private static boolean isSingleEdit(String left, String right) {
        int delta = left.length() - right.length();
        if (Math.abs(delta) > 1) return false;
        if (delta == 0) {
            int mismatches = 0;
            for (int i = 0; i < left.length(); i++) {
                if (left.charAt(i) != right.charAt(i) && ++mismatches > 1) return false;
            }
            return mismatches == 1;
        }
        String shorter = delta < 0 ? left : right;
        String longer = delta < 0 ? right : left;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < shorter.length() && j < longer.length()) {
            if (shorter.charAt(i) == longer.charAt(j)) {
                i++;
                j++;
            } else if (skipped) {
                return false;
            } else {
                skipped = true;
                j++;
            }
        }
        return true;
    }

    private static boolean asciiLetters(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'a' || ch > 'z') return false;
        }
        return true;
    }
}
