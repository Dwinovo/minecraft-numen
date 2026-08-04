package com.dwinovo.numen.client.agent.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Parser and local executor for the chat-side {@code /goal} command family. */
public final class GoalCommands {

    private GoalCommands() {}

    public record Result(boolean success, String text, GoalCommand command) {
        static Result ok(String text, GoalCommand command) {
            return new Result(true, text, command);
        }

        static Result fail(String text) {
            return new Result(false, text, null);
        }
    }

    public static boolean isGoalCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("/goal")
                || t.toLowerCase(Locale.ROOT).startsWith("/goal ")
                || t.toLowerCase(Locale.ROOT).startsWith("/goal\t");
    }

    public static List<String> allCommandTexts() {
        List<String> out = new ArrayList<>();
        out.add("/goal");
        for (GoalCommand command : GoalCommand.values()) {
            out.add("/goal " + command.text());
        }
        return out;
    }

    public static Result execute(GoalState goal, String raw, long nowMs) {
        GoalState state = Objects.requireNonNull(goal, "goal state");
        String rest = raw == null ? "" : raw.trim();
        if (rest.toLowerCase(Locale.ROOT).startsWith("/goal")) {
            rest = rest.substring(5).trim();
        }
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
        String verb = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        GoalCommand command = verb.isEmpty() ? GoalCommand.STATUS : GoalCommand.parse(verb);
        if (command == null) {
            GoalCommand suggestion = GoalCommand.typoSuggestion(verb);
            if (suggestion != null) {
                return Result.fail("无法识别 goal 子命令 \"" + verb + "\"，你是否想输入 /goal "
                        + suggestion.text() + "？若要把它作为目标内容，请使用 /goal add " + rest + "。");
            }
            return add(state, "/goal " + rest, rest, nowMs);
        }
        String normalized = verb.isEmpty() ? "/goal" : "/goal " + command.text();
        return switch (command) {
            case HELP -> Result.ok(helpText(), GoalCommand.HELP);
            case STATUS -> Result.ok(statusLine(state, nowMs), GoalCommand.STATUS);
            case LIST -> Result.ok(listLine(state), GoalCommand.LIST);
            case ADD -> add(state, normalized, arg, nowMs);
            case UPDATE -> update(state, normalized, arg, nowMs);
            case PROGRESS -> Result.ok(progressText(state, nowMs), GoalCommand.PROGRESS);
            case COMPLETE -> terminalMutation(state, normalized, nowMs, GoalCommand.COMPLETE,
                    () -> state.complete(nowMs),
                    "已标记 goal 完成");
            case CANCEL -> terminalMutation(state, normalized, nowMs, GoalCommand.CANCEL,
                    () -> state.cancel(nowMs),
                    "已取消 goal");
            case PAUSE -> mutation(state, normalized, nowMs, GoalCommand.PAUSE,
                    () -> state.pause(nowMs),
                    "已暂停 goal");
            case BLOCKED -> blocked(state, normalized, arg, nowMs);
            case RESUME -> mutation(state, normalized, nowMs, GoalCommand.RESUME,
                    () -> state.resume(nowMs),
                    "已恢复 goal");
            case RECENT -> Result.ok(recentText(state), GoalCommand.RECENT);
            case COMPACT -> compact(state, normalized, nowMs);
            case SETTINGS -> Result.ok(settingsText(), GoalCommand.SETTINGS);
        };
    }

    private static Result add(GoalState state, String command, String arg, long nowMs) {
        if (arg.isBlank()) return Result.fail("用法: /goal add <内容>");
        if (state.hasGoal() && !state.isTerminal()) {
            return Result.fail("已有一个进行中的 goal: " + state.title()
                    + "。完成或取消后再 /goal add。");
        }
        state.reset(arg, nowMs);
        state.recordCommand(command, "created: " + arg, nowMs);
        return Result.ok("已创建 goal: " + arg, GoalCommand.ADD);
    }

    private static Result update(GoalState state, String command, String arg, long nowMs) {
        if (!state.hasGoal() || state.isTerminal()) {
            return Result.fail("没有可更新的当前 goal，先 /goal add <内容>。");
        }
        if (arg.isBlank()) return Result.fail("用法: /goal update <内容>");
        boolean ok = state.updateTitle(arg, nowMs);
        if (!ok) return Result.fail("goal 标题不能为空。");
        state.recordCommand(command, "updated: " + arg, nowMs);
        return Result.ok("已更新 goal: " + arg, GoalCommand.UPDATE);
    }

    private static Result mutation(GoalState state, String command, long nowMs,
                                   GoalCommand goalCommand,
                                   java.util.function.BooleanSupplier action, String successText) {
        if (!state.hasGoal() || state.isTerminal()) {
            return Result.fail("没有可操作的当前 goal。");
        }
        boolean ok = action.getAsBoolean();
        if (!ok) return Result.fail("当前 goal 状态不允许这个操作。");
        state.recordCommand(command, successText, nowMs);
        return Result.ok(successText, goalCommand);
    }

    private static Result terminalMutation(GoalState state, String command, long nowMs,
                                           GoalCommand goalCommand,
                                           java.util.function.BooleanSupplier action,
                                           String successText) {
        if (!state.hasGoal() || state.isTerminal()) {
            return Result.fail("没有可完成的当前 goal。");
        }
        boolean ok = action.getAsBoolean();
        if (!ok) return Result.fail("当前 goal 状态不允许这个操作。");
        state.recordCommand(command, successText, nowMs);
        return Result.ok(successText, goalCommand);
    }

    private static Result compact(GoalState state, String command, long nowMs) {
        if (!state.hasGoal() || state.isTerminal()) {
            return Result.fail("没有可压缩上下文的当前 goal。");
        }
        state.setCompactRequested(true, nowMs);
        state.recordCommand(command, "compact requested", nowMs);
        return Result.ok("已请求上下文压缩，将在可执行时处理", GoalCommand.COMPACT);
    }

    private static Result blocked(GoalState state, String command, String reason, long nowMs) {
        if (reason.isBlank()) return Result.fail("用法: /goal blocked <原因>");
        if (!state.hasGoal() || state.isTerminal()) {
            return Result.fail("没有可阻塞的当前 goal。");
        }
        if (!state.block(reason, nowMs)) return Result.fail("当前 goal 状态不允许标记为阻塞。");
        state.recordCommand(command + " " + reason, "blocked: " + reason, nowMs);
        return Result.ok("已标记 goal 阻塞: " + reason, GoalCommand.BLOCKED);
    }

    private static String helpText() {
        return "goal 指令: /goal <内容> 直接创建, /goal, /goal help, /goal list, /goal add <内容>, "
                + "/goal update <内容>, /goal status, /goal progress, /goal complete, "
                + "/goal cancel, /goal pause, /goal blocked <原因>, /goal resume, /goal recent, /goal compact, "
                + "/goal settings";
    }

    private static String statusLine(GoalState state, long nowMs) {
        if (!state.hasGoal()) return "还没有 goal，输入 /goal add <内容> 创建。";
        String stateText = switch (state.status()) {
            case ACTIVE -> "进行中";
            case PAUSED -> "已暂停";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case FAILED -> "失败";
            case BLOCKED -> "已阻塞";
            case NONE -> "未使用";
        };
        String elapsed = formatDuration(state.effectiveElapsedMs(nowMs));
        String progress = state.totalTodoCount() > 0
                ? " | 进度: " + state.completedTodoCount() + "/" + state.totalTodoCount() : "";
        return "goal: " + state.title() + " | 状态: " + stateText
                + " | 耗时: " + elapsed + progress;
    }

    private static String listLine(GoalState state) {
        return state.hasGoal()
                ? "当前 goal: " + state.title() + " (" + state.status().key() + ")"
                : "没有 goal。";
    }

    private static String progressText(GoalState state, long nowMs) {
        if (!state.hasGoal()) return "还没有 goal，输入 /goal add <内容> 创建。";
        StringBuilder sb = new StringBuilder(statusLine(state, nowMs));
        if (!state.currentTask().isBlank()) sb.append(" | 当前任务: ").append(state.currentTask());
        if (!state.lastError().isBlank()) sb.append(" | 错误: ").append(state.lastError());
        List<GoalTodo> todos = state.todos();
        if (!todos.isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < todos.size(); i++) {
                GoalTodo todo = todos.get(i);
                String glyph = switch (todo.status()) {
                    case "completed" -> "✔";
                    case "in_progress" -> "▸";
                    default -> "○";
                };
                sb.append(glyph).append(' ').append(todo.content());
                if (i < todos.size() - 1) sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String recentText(GoalState state) {
        List<GoalCommandEntry> history = state.history();
        if (history.isEmpty()) return "暂无 goal 操作记录。";
        StringBuilder sb = new StringBuilder("最近 goal 操作:");
        int start = Math.max(0, history.size() - 10);
        for (int i = history.size() - 1; i >= start; i--) {
            GoalCommandEntry entry = history.get(i);
            sb.append("\n").append(entry.command());
            if (!entry.result().isBlank()) sb.append(" → ").append(entry.result());
        }
        return sb.toString();
    }

    private static String settingsText() {
        return "goal 设置: 自动继续=开; 只有 /goal cancel 会取消; "
                + "命令历史上限=" + GoalState.MAX_COMMAND_HISTORY + "; 状态按实体持久化。";
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m " + (sec % 60) + "s";
        long hour = min / 60;
        if (hour < 24) return hour + "h " + (min % 60) + "m";
        long day = hour / 24;
        return day + "d " + (hour % 24) + "h";
    }
}
