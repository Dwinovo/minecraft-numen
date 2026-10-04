package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;

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

    private static final Param<String> SKILL = Param.required("skill", ArgType.string(),
            "The skill's name, as <available_skills> lists it.");
    private static final Param<String> FILE = Param.optional("file", ArgType.string(),
            "Relative path of a supporting file the skill's text names, e.g. references/baroque.md.")
            .whenOmitted("load the skill's own text");
    private static final List<Param<?>> PARAMS = List.of(SKILL, FILE, Listing.PAGE);

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
        return Param.schemaOf(PARAMS);
    }

    @Override
    public void invoke(ToolCall call) {
        CommandArgs args;
        try {
            args = CommandArgs.fromJson(PARAMS, call.args());
        } catch (IllegalArgumentException bad) {
            call.complete(TaskResult.fail(ErrorKind.BAD_ARGUMENT, bad.getMessage(), null).toJson());
            return;
        }
        call.complete(load(args));
    }

    /**
     * 技能正文(或它的一个附属文件)要的那一页,原文交给模型;没有这份技能、没有这个文件、没有这一页时是一条失败,说清有什么。
     */
    private static String load(CommandArgs args) {
        SkillRegistry registry = SkillRegistry.instance();
        String name = args.get(SKILL);
        String file = args.get(FILE);
        String text;
        if (file != null) {
            try {
                text = SkillInjection.supportFile(name, file, registry.readSupportFile(name, file));
            } catch (IllegalArgumentException missing) {
                return TaskResult.fail(ErrorKind.NOT_FOUND, missing.getMessage(), null).toJson();
            }
        } else {
            Optional<SkillInfo> skill = registry.get(name);
            if (skill.isEmpty()) {
                String known = registry.available().stream().map(SkillInfo::name)
                        .collect(Collectors.joining(", "));
                return TaskResult.fail(ErrorKind.NOT_FOUND, "unknown skill: " + name + "; the skills are: "
                        + (known.isEmpty() ? "(none available)" : known), null).toJson();
            }
            text = SkillInjection.body(skill.get(), null);
        }
        TaskResult page = new Listing("", List.of(text.split("\n", -1)), "").result(args);
        return page.success() ? page.message() : page.toJson();
    }
}
