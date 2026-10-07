package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 装技能的工具,照 Claude Code 的 Skill 工具:她在系统提示的 {@code <available_skills>} 里看到一份技能合用,调它,技能正文就是这次
 * 调用的结果。技能表在主人客户端({@link SkillRegistry}),工具当场在那里答;外接大脑(MCP)调的是同一个工具。
 *
 * <p>正文成型交给 {@link SkillInjection},和主人打斜杠命令进上下文的是同一个样子。技能与附属文件是文件,按输出预算一页一页给
 * ({@link Listing}),和 pi、Claude Code 读文件一样。
 */
public final class SkillTool implements NumenTool {

    /** 工具名;技能表的抬头({@link SkillRegistry#formatXml})照它写。 */
    public static final String NAME = "skill";


    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 照 Claude Code 的 Skill 工具写:动词起头,说清什么时候用、结果是什么
        return "Loads a skill — the detailed workflow for one kind of task — and returns its instructions as this "
                + "call's result.\n"
                + "- <available_skills> in the system prompt lists each skill and what it is for. When a task you "
                + "are asked to do matches one, load it before you start on the task, then follow it.\n"
                + "- A loaded skill is a <skill_content name=\"…\"> block in the conversation, whether this tool or "
                + "your owner's slash command put it there; while it is in the conversation, follow it from there.\n"
                + "- A skill's text may name a supporting file by relative path; load that with file when the text "
                + "sends you there.\n"
                + "- A long text comes a page at a time; its last line says which page to ask for next.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("skill", "The skill's name, as <available_skills> lists it.")
                .optionalString("file", "Relative path of a supporting file the skill's text names, e.g. "
                        + "references/baroque.md. Omit to load the skill's own text.")
                .optionalInteger("page", "Which page of a long text. Omit for the first.", 1, 99)
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        String result;
        try {
            result = load(call.args());
        } catch (IllegalArgumentException bad) {
            result = ToolOutcome.failure(bad.getMessage());
        }
        call.complete(result);
    }

    /**
     * 技能正文(或它的一个附属文件)要的那一页,原文交给模型;没有这份技能、没有这个文件、没有这一页时是一条失败,说清有什么。
     */
    private static String load(JsonObject args) {
        SkillRegistry registry = SkillRegistry.instance();
        String name = text(args, "skill");
        if (name == null) {
            throw new IllegalArgumentException("argument 'skill' is missing: the skill's name");
        }
        String file = text(args, "file");
        String text;
        if (file != null) {
            try {
                text = SkillInjection.supportFile(name, file, registry.readSupportFile(name, file));
            } catch (IllegalArgumentException missing) {
                return ToolOutcome.failure(missing.getMessage());
            }
        } else {
            Optional<SkillInfo> skill = registry.get(name);
            if (skill.isEmpty()) {
                String known = registry.available().stream().map(SkillInfo::name)
                        .collect(Collectors.joining(", "));
                return ToolOutcome.failure("unknown skill: " + name + "; the skills are: "
                        + (known.isEmpty() ? "(none available)" : known));
            }
            text = SkillInjection.body(skill.get(), null);
        }
        JsonElement page = args.get("page");
        return new Listing(List.of(text.split("\n", -1))).page(page == null || page.isJsonNull() ? 1 : page.getAsInt());
    }

    private static String text(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
