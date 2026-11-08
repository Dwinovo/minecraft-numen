package com.dwinovo.numen.api.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.client.agent.RemoteCompanion;
import com.dwinovo.numen.api.client.agent.ServerLibraries;

import java.util.ArrayList;
import java.util.List;

/**
 * 每个技能一条命令:{@code /build 在河边盖个木屋}。
 *
 * <p>按服务器上当前的技能表现算(技能目录是服务器全局的)——往 {@code config/numen/server/skills} 里丢个目录、或者管理员在面板里
 * 把某个技能关掉,命令表跟着变,不用重启也不用去哪儿登记。
 */
final class SkillCommandSource implements CommandSource {

    @Override
    public List<ChatCommand> commands(RemoteCompanion loop) {
        List<ChatCommand> out = new ArrayList<>();
        for (ServerLibraries.Skill skill : ServerLibraries.skillsEffective()) {
            // 关掉的技能不该还在补全里躺着:面板上把它关了,命令就得跟着消失,
            // 否则打得出来、按下去却说"已经不在了"。
            if (skill.enabled()) {
                out.add(new SkillCommand(skill.name()));
            }
        }
        return out;
    }

    /**
     * 一条技能命令。
     *
     * <p>只记名字:正文由服务端在<b>执行那一刻</b>从技能目录里现取并展开,否则
     * 主人刚改过的 SKILL.md 不会生效,而补全列表是很早以前建的。
     */
    private record SkillCommand(String skill) implements ChatCommand {

        @Override
        public String name() {
            return skill;
        }

        @Override
        public String description() {
            String desc = ServerLibraries.skillsEffective().stream().filter(s -> s.name().equals(skill))
                    .map(ServerLibraries.Skill::description).findFirst().orElse(null);
            return desc == null || desc.isBlank() ? I18n.get(Keys.CMD_SKILL) : desc;
        }

        @Override
        public String argHint() {
            return I18n.get(Keys.CMD_SKILL_ARGS);
        }

        @Override
        public boolean touchesContext() {
            return true;
        }

        /**
         * 把技能正文交给她,顺带捎上主人的要求。
         *
         * <p>正文进的是 {@code <query>} <b>外面</b>——模型看得到,聊天流只显示主人打的
         * 那行命令({@link com.dwinovo.numen.api.client.chat.OwnerWordsMode} 只取标记里的)。
         * 所以这里不用再回一句话,主人的气泡本身就是回执。
         */
        @Override
        public String run(RemoteCompanion loop, String args) {
            boolean there = ServerLibraries.skillsEffective().stream().anyMatch(s -> s.name().equals(skill) && s.enabled());
            if (!there) {
                return I18n.get(Keys.CMD_SKILL_GONE, skill);
            }
            loop.submitSkill(ChatCommands.PREFIX + skill + (args.isBlank() ? "" : " " + args), skill, args);
            return null;
        }
    }
}
