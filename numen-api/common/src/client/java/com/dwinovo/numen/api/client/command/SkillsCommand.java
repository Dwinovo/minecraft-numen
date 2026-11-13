package com.dwinovo.numen.api.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.agent.RemoteCompanion;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.ui.widget.SelectPanel;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /skills} —— 技能面板:一行一个,回车开关可见性。
 *
 * <p>与设置页的技能管理<b>同源</b>:两边读的都是服务器上的技能表(库协议 {@code skill}),开关由服务端判——
 * 只有管理员拨得动。没有第二份状态,也就没有要对齐的东西。
 *
 * <p>对着一只分享来的同伴时,列的是她用得了的技能——她创建者的那一份({@link ServerLibraries#skillsOf}),只看不能拨:
 * 她的技能由创建者管理,标题上写明。
 */
final class SkillsCommand implements PopupCommand {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return I18n.get(Keys.CMD_SKILLS);
    }

    @Override
    public com.dwinovo.numen.api.client.ui.widget.Popup popup(RemoteCompanion loop) {
        NumenRoster.Entry her = loop == null ? null : NumenRoster.instance().entry(loop.entityUuid());
        return new SelectPanel(her != null && her.sharedWith(RemoteCompanions.viewer())
                ? new CreatorsSkillsPage(her) : new SkillsPage());
    }

    /** 分享来的她用得了的技能:创建者的那一份,只读。 */
    private record CreatorsSkillsPage(NumenRoster.Entry her) implements SelectPanel.Page {

        @Override
        public String title() {
            return I18n.get(Keys.CMD_SKILLS_BY_CREATOR, her.creator().shown());
        }

        @Override
        public List<SelectPanel.Row> rows() {
            List<SelectPanel.Row> out = new ArrayList<>();
            for (ServerLibraries.HerSkill skill : ServerLibraries.skillsOf(her.uuid(), RemoteCompanions.viewer())) {
                out.add(new SelectPanel.Row(skill.name(), skill.description() == null ? "" : skill.description(), null));
            }
            if (out.isEmpty()) {
                out.add(new SelectPanel.Row(I18n.get(Keys.CMD_SKILLS_EMPTY), "", null));
            }
            return out;
        }

        @Override
        public boolean activate(int index) {
            return false;   // 她的技能由创建者管理,这里拨不动
        }
    }

    /** 面板内容。每次 {@link #rows} 都现问库——设置页那边改了,这边下一帧就对。 */
    private static final class SkillsPage implements SelectPanel.Page {

        @Override
        public String title() {
            return I18n.get(Keys.CMD_SKILLS_TITLE);
        }

        @Override
        public List<SelectPanel.Row> rows() {
            List<SelectPanel.Row> out = new ArrayList<>();
            for (ServerLibraries.Skill skill : ServerLibraries.skillsEffective()) {
                String desc = skill.description() == null || skill.description().isBlank()
                        ? "" : skill.description();
                out.add(new SelectPanel.Row(skill.name(), desc, skill.enabled()));
            }
            if (out.isEmpty()) {
                out.add(new SelectPanel.Row(I18n.get(Keys.CMD_SKILLS_EMPTY), I18n.get(Keys.CMD_SKILLS_EMPTY_HINT), null));
            }
            return out;
        }

        @Override
        public boolean activate(int index) {
            List<ServerLibraries.Skill> all = ServerLibraries.skillsEffective();
            if (index < 0 || index >= all.size()) {
                return false;   // 空库时那条占位行:按了也没有东西可开关
            }
            ServerLibraries.Skill skill = all.get(index);
            JsonObject body = new JsonObject();
            body.addProperty("id", skill.base().id());
            RemoteCompanions.library().request(Libraries.SKILL, skill.enabled() ? Libraries.DISABLE : Libraries.ENABLE, body);
            return true;
        }
    }
}
