package com.dwinovo.numen.api.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.brain.Libraries;
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
        return new SelectPanel(new SkillsPage());
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
