package com.dwinovo.numen.api.agent.tool;

/**
 * 一个工具的性质,工具自己在定义处写明(判据只在这里):交给想知道"这个工具会不会动东西"的一方(外接大脑的 MCP 端点把它标成
 * 工具注解,客户端据此决定要不要弹确认)。写的是工具<b>真实</b>的行为,拿不准按最保守的写。
 *
 * @param title       给人看的名字;没有是 {@code null}
 * @param readOnly    不改任何东西:调多少次世界、她的笔记与计划都和调之前一样
 * @param destructive 可能毁掉已有的东西(拆方块、杀生物、忘掉一条笔记),不只是添加;只在 {@code readOnly} 为假时有意义
 * @param idempotent  同样的参数再调一次没有额外效果;只在 {@code readOnly} 为假时有意义
 * @param openWorld   触及自己能掌握的范围之外(任何可能发生在世界里的事),不是只读写她自己的簿子
 */
public record ToolHints(String title, boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

    /** 什么都没声明的工具(接进来的别家 MCP 工具):按最保守的写——能改东西、可能毁东西、可能不幂等、触及外面。 */
    public static final ToolHints UNKNOWN = new ToolHints(null, false, true, false, true);

    /** 只读她自己簿子(技能)的工具。 */
    public static ToolHints reads(String title) {
        return new ToolHints(title, true, false, true, false);
    }
}
