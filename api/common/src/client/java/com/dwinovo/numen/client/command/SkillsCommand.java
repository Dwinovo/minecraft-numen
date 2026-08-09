package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;

/**
 * {@code /skills} —— 列出可用技能。
 *
 * <p>纯查看:只写给主人看,她那边什么都不会发生。跟 {@code /build} 正好是两类命令的
 * 样板,{@link ChatCommand#touchesContext} 那个记号区分的就是它俩。
 */
final class SkillsCommand implements ChatCommand {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "列出可用技能";
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        var all = SkillRegistry.instance().all();
        if (all.isEmpty()) {
            return "还没有技能。往 config/numen/skills 里放一个带 SKILL.md 的目录就行。";
        }
        StringBuilder sb = new StringBuilder("可用技能(直接打 /名字 就能用):");
        for (SkillInfo info : all) {
            sb.append('\n').append(ChatCommands.PREFIX).append(info.name());
            if (info.description() != null && !info.description().isBlank()) {
                sb.append("  ").append(info.description());
            }
        }
        return sb.toString();
    }
}
