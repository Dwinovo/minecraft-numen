package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.ClientSource;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.tools.AgentOps;

import java.util.List;

/**
 * {@code skill}:把一份技能的说明装进对话。
 *
 * <p>技能表在主人客户端({@code SkillRegistry}),所以在客户端执行;命令树两侧都登记(帮助要它)。{@code load} 调得勤,
 * 提升为快捷工具 {@code load_skill}:参数是 {@code name}、{@code file} 与翻页的 {@code page};快捷工具与 {@code skill load}
 * 是同一个处理函数、同一份回执。技能与附属文件是文件,按输出预算一页一页读,和 pi、Claude Code 读文件一样。
 */
public final class SkillCommands {

    static final String GROUP = "skill";
    static final String LOAD = "load";

    private static final Param<String> NAME = Param.required("name", ArgType.string(),
            "The skill name from <available_skills> in the system prompt.");
    private static final Param<String> FILE = Param.optional("file", ArgType.string(),
            "Relative path of a supporting file referenced by the skill body, e.g. references/baroque.md.")
            .whenOmitted("load the skill body itself");

    private static final AgentOps SKILLS = new AgentOps();

    private SkillCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Skills: detailed workflows you load when a task matches one.",
                SkillCommands::actions);
    }

    private static void actions(CommandGroup skill) {
        skill.client(LOAD, "Load a skill's instructions when the task at hand matches one listed in "
                        + "<available_skills>.",
                SkillCommands::load, NAME, FILE, Listing.PAGE)
                .example(GROUP + " " + LOAD + " containers")
                .example(GROUP + " " + LOAD + " building_design --file references/baroque.md")
                .note("A skill body may reference supporting files by relative path; load one with --file only "
                        + "when the body points you there.")
                .note("A long skill or file comes a page at a time; the last line says how to get the next.")
                .promote("load_skill", """
                        Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

                        Use this tool to inject the skill's instructions and resources into the current conversation. The output contains detailed workflow guidance for the task.

                        The skill name must match one of the skills listed in your system prompt's <available_skills> block.

                        A skill body may reference supporting files by relative path (e.g. references/roofs.md). Call this tool again with the same name plus `file` to read one — only when the body points you there.""");
    }

    private static void load(ClientSource src, CommandArgs args) {
        src.reply(SKILLS.loadSkill(args.get(NAME), args.get(FILE), args,
                args.write(GROUP + " " + LOAD, List.of(NAME, FILE))));
    }
}
