package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.goal.GoalCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side slash-command completion source. It seeds the {@code /goal}
 * family, the built-in {@code /numen} verbs, and aliases; agent tools from
 * {@link ToolRegistry} are read live so MCP enable/disable changes show up on
 * the next keystroke.
 */
public final class CommandRegistry {

    private static final CommandRegistry INSTANCE = new CommandRegistry();

    private final Map<String, CommandCandidate> fixed = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, CommandCandidate> external = new LinkedHashMap<>();

    public CommandRegistry() {
        seed();
    }

    public static CommandRegistry instance() {
        return INSTANCE;
    }

    public void register(CommandCandidate candidate) {
        if (candidate == null || candidate.command() == null || candidate.command().isBlank()) return;
        external.put(normalize(candidate.command()), candidate);
    }

    public List<CommandCandidate> all() {
        Map<String, CommandCandidate> merged = new LinkedHashMap<>(fixed);
        merged.putAll(external);
        for (NumenTool tool : ToolRegistry.all()) {
            String command = "/" + tool.name().toLowerCase(Locale.ROOT);
            merged.putIfAbsent(command,
                    new CommandCandidate(command, compactDescription(tool.description()), "tool"));
        }
        return List.copyOf(merged.values());
    }

    public List<CommandCandidate> candidates(String input) {
        String raw = input == null ? "" : input.trim();
        if (!raw.startsWith("/")) return List.of();
        String lower = raw.toLowerCase(Locale.ROOT);
        List<CommandCandidate> out = new ArrayList<>();
        for (CommandCandidate candidate : all()) {
            if (candidate.command().toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
                continue;
            }
            String target = aliases.get(lower);
            if (target != null && target.equalsIgnoreCase(candidate.command())) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private void seed() {
        add("/goal", "查看当前 goal，或 /goal <内容> 直接创建", "goal");
        for (GoalCommand command : GoalCommand.values()) {
            add("/goal " + command.text(), command.help(), "goal",
                    command.requiresArgument());
        }
        aliases.put("/g", "/goal");
        aliases.put("/go", "/goal");

        add("/numen", "查看 Numen 命令", "numen");
        add("/numen player", "管理同伴", "numen");
        add("/numen player summon", "召唤同伴", "numen");
        add("/numen player despawn", "永久移除同伴", "numen");
        add("/numen settings", "打开设置面板", "numen");
        add("/numen reset", "重置对话循环", "numen");
        aliases.put("/n", "/numen");
    }

    private void add(String command, String description, String group) {
        add(command, description, group, false);
    }

    private void add(String command, String description, String group, boolean requiresArgument) {
        fixed.put(normalize(command),
                new CommandCandidate(command, description, group, requiresArgument));
    }

    private static String normalize(String command) {
        String c = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        while (c.contains("  ")) c = c.replace("  ", " ");
        return c;
    }

    private static String compactDescription(String raw) {
        if (raw == null || raw.isBlank()) return "agent tool";
        String flat = raw.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return flat.length() <= 90 ? flat : flat.substring(0, 87) + "...";
    }
}
