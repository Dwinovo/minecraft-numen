package com.dwinovo.numen.agent.skill;

/**
 * 技能正文进对话时长什么样——<b>唯一</b>的成型口。
 *
 * <h2>为什么要单独一个类</h2>
 * 技能有两个扳机:模型自己判断该用({@code load_skill} 工具),主人直接指定(聊天框里的
 * {@code /skill})。扳机不同,但进上下文的东西必须一模一样——否则模型读到的"同一个技能"
 * 会因为来路不同而长得不一样,而这种差异只在其中一条路上出问题,极难查。
 *
 * <p>所以成型只在这里做一次,两条路都来这儿取。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class SkillInjection {

    private SkillInjection() {}

    /**
     * 技能正文。
     *
     * @param arguments 主人随命令捎带的要求;空则不缀。缀在正文<b>尾部、同一条里</b>——
     *                  拆成两条的话,模型读完长长的正文容易忘了主人到底要什么
     */
    public static String body(SkillInfo info, String arguments) {
        StringBuilder out = new StringBuilder(info.content().length() + 160);
        out.append("<skill_content name=\"").append(escapeXmlAttr(info.name())).append("\">\n");
        out.append("# Skill: ").append(info.name()).append("\n\n");
        out.append(info.content().trim());
        out.append("\n</skill_content>");
        if (arguments != null && !arguments.isBlank()) {
            out.append("\nARGUMENTS: ").append(arguments.trim());
        }
        return out.toString();
    }

    /** 正文引用的附属文件(三级披露:清单 → 正文 → 附属文件)。 */
    public static String supportFile(String skillName, String relPath, String text) {
        return "<skill_file skill=\"" + escapeXmlAttr(skillName) + "\" path=\""
                + escapeXmlAttr(relPath) + "\">\n" + text.trim() + "\n</skill_file>";
    }

    public static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
