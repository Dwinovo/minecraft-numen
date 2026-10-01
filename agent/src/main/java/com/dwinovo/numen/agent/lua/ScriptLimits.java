package com.dwinovo.numen.agent.lua;

/**
 * 一段脚本跑一次的上限,数值只在这里。到了就停在当前那一行,回执如实说停在哪、因为哪一条。
 */
public final class ScriptLimits {

    private ScriptLimits() {}

    /**
     * 一次运行最多执行几条命令(嵌套跑的脚本算在一起)。挖一块区域每一圈是"看还剩没有、走过去、挖"三条,两百条够七十来圈——
     * 一整块矿脉挖空用不了这么多;再多就是循环条件写错了在空转,该停下让她看一眼。
     */
    public static final int COMMANDS = 200;

    /**
     * 一次运行最长多久(墙钟,毫秒):二十分钟,原版的一整天。一段脚本跑过一天,世界早已不是她写它时看到的样子。
     * 只在命令之间查:等身体收尾时不打断那件活,活有自己的期限。
     */
    public static final long WALL_MILLIS = 20L * 60L * 1000L;

    /**
     * 两次调命令之间最多执行多少条 Lua 指令。脚本在大脑的线程上跑(主人客户端的主线程、评测时服务端的主线程),
     * 正常的脚本在两条命令之间只做几十上百条指令的判断与拼接;一百万条约是几十毫秒,再多就是死循环,中断它,
     * 不让游戏卡住。
     */
    public static final int INSTRUCTIONS_PER_SLICE = 1_000_000;

    /** 数指令的粒度:每执行这么多条查一次。 */
    static final int INSTRUCTION_CHECK = 1_000;

    /** {@code print} 写进回执的文字最多多少字;超出的截掉并说明。 */
    public static final int PRINTED_CHARS = 2_000;
}
