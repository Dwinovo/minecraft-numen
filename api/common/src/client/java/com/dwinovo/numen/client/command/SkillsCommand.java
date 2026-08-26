package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.widget.SelectPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /skills} —— 技能面板:一行一个,回车开关可见性。
 *
 * <p>与设置页的技能管理<b>同源</b>:两边写的都是 {@link SkillRegistry} 这一个单例,
 * 落盘也是它自己的 {@code skills_state.json}。没有第二份状态,也就没有要对齐的东西。
 */
final class SkillsCommand implements PopupCommand {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "技能开关";
    }

    @Override
    public com.dwinovo.numen.client.ui.widget.Popup popup(EntityAgentLoop loop) {
        return new SelectPanel(new SkillsPage());
    }

    /** 面板内容。每次 {@link #rows} 都现问库——设置页那边改了,这边下一帧就对。 */
    private static final class SkillsPage implements SelectPanel.Page {

        @Override
        public String title() {
            return "技能   ↑↓ 选择 · 回车开关 · Esc 返回";
        }

        @Override
        public List<SelectPanel.Row> rows() {
            SkillRegistry reg = SkillRegistry.instance();
            List<SelectPanel.Row> out = new ArrayList<>();
            for (SkillInfo info : reg.all()) {
                String desc = info.description() == null || info.description().isBlank()
                        ? "" : info.description();
                out.add(new SelectPanel.Row(info.name(), desc, !reg.isDisabled(info.name())));
            }
            if (out.isEmpty()) {
                out.add(new SelectPanel.Row("(还没有技能)",
                        "往 config/numen/skills 放一个带 SKILL.md 的目录", null));
            }
            return out;
        }

        @Override
        public boolean activate(int index) {
            SkillRegistry reg = SkillRegistry.instance();
            List<SkillInfo> all = new ArrayList<>(reg.all());
            if (index < 0 || index >= all.size()) {
                return false;   // 空库时那条占位行:按了也没有东西可开关
            }
            String name = all.get(index).name();
            reg.setEnabled(name, reg.isDisabled(name));   // 翻面
            return true;
        }
    }
}
