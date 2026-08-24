package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.ArrayList;
import java.util.List;

/**
 * 每个技能一条命令:{@code /build 在河边盖个木屋}。
 *
 * <p>按当前技能库现算——主人往 {@code config/numen/skills} 里丢个目录、或者在面板里
 * 把某个技能关掉,命令表跟着变,不用重启也不用去哪儿登记。
 */
final class SkillCommandSource implements CommandSource {

    @Override
    public List<ChatCommand> commands(EntityAgentLoop loop) {
        SkillRegistry registry = SkillRegistry.instance();
        List<ChatCommand> out = new ArrayList<>();
        for (SkillInfo info : registry.all()) {
            // 关掉的技能不该还在补全里躺着:面板上把它关了,命令就得跟着消失,
            // 否则打得出来、按下去却说"已经不在了"。
            if (!registry.isDisabled(info.name())) {
                out.add(new SkillCommand(info.name()));
            }
        }
        return out;
    }

    /**
     * 一条技能命令。
     *
     * <p>只记名字不记 {@link SkillInfo}:正文要在<b>执行那一刻</b>去库里现取,否则
     * 主人刚改过的 SKILL.md 不会生效,而补全列表是很早以前建的。
     */
    private record SkillCommand(String skill) implements ChatCommand {

        @Override
        public String name() {
            return skill;
        }

        @Override
        public String description() {
            String desc = SkillRegistry.instance().get(skill)
                    .map(SkillInfo::description).orElse(null);
            return desc == null || desc.isBlank() ? "技能" : desc;
        }

        @Override
        public String argHint() {
            return "[要求]";
        }

        @Override
        public boolean touchesContext() {
            return true;
        }

        /**
         * 这条命令是要发给模型的,所以没绑模型/没填 key 时它就是用不了的。
         *
         * <p>放在这儿而不是执行时再报:补全列表里当场灰掉并写出理由,主人打之前就知道,
         * 不会按下回车之后什么都没发生。
         */
        @Override
        public String unavailable(EntityAgentLoop loop) {
            return loop == null ? null : loop.endpointProblem();
        }

        /**
         * 把技能正文交给她,顺带捎上主人的要求。
         *
         * <p>正文进的是 {@code <query>} <b>外面</b>——模型看得到,聊天流只显示主人打的
         * 那行命令({@link com.dwinovo.numen.client.chat.OwnerWordsMode} 只取标记里的)。
         * 所以这里不用再回一句话,主人的气泡本身就是回执。
         */
        @Override
        public String run(EntityAgentLoop loop, String args) {
            SkillInfo info = SkillRegistry.instance().get(skill).orElse(null);
            if (info == null) {
                return "技能 " + skill + " 已经不在了(被关掉或删掉了)。";
            }
            loop.submitCommand(ChatCommands.PREFIX + skill + (args.isBlank() ? "" : " " + args),
                    SkillInjection.body(info, args));
            return null;
        }
    }
}
