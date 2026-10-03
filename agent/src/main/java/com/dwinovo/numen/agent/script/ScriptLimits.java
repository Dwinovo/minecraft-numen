package com.dwinovo.numen.agent.script;

/**
 * 一段脚本跑一次的上限,数值只在这里。到了就停在当前那一行,回执如实说停在哪、因为哪一条。
 */
public final class ScriptLimits {

    private ScriptLimits() {}

    /**
     * 一次运行最多几次 API 调用(嵌套跑的脚本算在一起)。挖一块区域每一圈是"看还剩没有、走过去、挖"几次,两百次够几十圈——
     * 一整块矿脉挖空用不了这么多;再多就是循环条件写错了在空转,该停下让她看一眼。
     */
    public static final int COMMANDS = 200;

    /**
     * 一次运行最长多久(墙钟,毫秒):二十分钟,原版的一整天。一段脚本跑过一天,世界早已不是她写它时看到的样子。
     * 只在调用之间查:等身体收尾时不打断那件活,活有自己的期限。
     */
    public static final long WALL_MILLIS = 20L * 60L * 1000L;

    /**
     * 两次 API 调用之间最多执行多少条脚本指令。脚本跑在自己的虚拟线程上,但两次调用之间大脑的线程(主人客户端的主线程、
     * 评测时服务端的主线程)等它算完;正常的脚本在两次调用之间只做几十上百条指令的判断与拼接,一百万条约是几十毫秒,
     * 再多就是死循环,中断它,不让游戏卡住。
     */
    public static final int INSTRUCTIONS_PER_SLICE = 1_000_000;

    /**
     * 一次运行一共最多执行多少条脚本指令:每一段有每一段的上限,这条管跨调用累积起来的表与数据(一条指令至多往表里放一项),
     * 一千万条约是两百段写满,正常的脚本差几个数量级。
     */
    public static final long INSTRUCTIONS = 10_000_000;

    /**
     * 一次运行一共最多为字符串分配多少字节:字符串操作不算指令,{@code s = s .. s} 翻倍几十次就能吃光内存。64 MB:脚本里的字符串是
     * 调用的参数、回执与打印,一次运行用得着的是它的千分之一。
     */
    public static final long STRING_BYTES = 64L << 20;

    /** {@code print} 写进回执的文字最多多少字;超出的截掉并说明。 */
    public static final int PRINTED_CHARS = 2_000;
}
