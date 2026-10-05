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
     * 两次 API 调用之间最多执行多少条脚本指令。脚本跑在自己的虚拟线程上,两次调用之间驱动它的那条线程(服务端上这段程序自己的执行体,
     * 不是主线程)等它算完;正常的脚本在两次调用之间只做几十上百条指令的判断与拼接,一百万条约是几十毫秒,
     * 再多就是死循环,中断它。
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

    /**
     * {@code print} 写进回执 stdout 一栏的文字最多多少字;超出的截掉,写明截了多少。API 返回的是数据,要看就得 print:一组函数的类型签名
     * ({@code numen.api.help("numen.build")})与一页查询结果要装得下,再多就该在脚本里筛过再打。
     */
    public static final int PRINTED_CHARS = 6_000;

    /**
     * 回执 stderr 一栏里一条最多多少字:一件占身体的活的整段实际账(挖了什么、放了什么、路上改了什么)写成一条,超出的整行丢掉,写明
     * 还有多少字没显示。
     */
    public static final int STDERR_RECORD_CHARS = 4_000;

    /**
     * 回执 stderr 一栏合起来最多多少字,超出的条按先后保留头部、写明"另外 N 条省略"。回执是给模型读的——它进模型的上下文,所以预算按模型
     * 读得下定,不是按线能送多大;一万两千字约三千个词元,够几十件活各写一两百字的账。连续相同的条先合并成一条,所以循环里重复的话
     * 只占一条。回执因此按构造远小于一个下行包({@code Wire}),不靠"装不下再缩"。
     */
    public static final int STDERR_CHARS = 12_000;

    /** 回执文字里 {@code return} 的值最多多少字,超出的截掉并说明(和 {@link #PRINTED_CHARS} 同一类:给模型读的;程序的返回值原样只在服务端进程里,不上网线)。 */
    public static final int RETURNED_CHARS = 6_000;

    /**
     * 评测按函数统计时,每次调用写成的文字最多多少字:超出的头部留下、尾部换成整段文字的摘要,所以"和之前一字不差"照样认得出,
     * 而一次带上千格参数的调用不会让每次调用的结局跟着变大。
     */
    public static final int CALL_TEXT_CHARS = 240;

    // ---- 显示一个值(print、return、stderr 里的值共用,见 LuaDisplay) ----
    // 照 NumPy 的 printoptions(threshold = 1000,edgeitems = 3:数组超过一千个数才缩略,缩略时首尾各三个)和 pandas 的
    // display.max_rows = 60(超过六十行只显示首尾各五行)。它们的元素是一个数;我们的元素是一张表(一个方块 {block, pos}、一只实体),
    // 一项就是一百来个字,所以阈值小得多:二十项以内全部显示(背包的二十来种东西、一页查询结果能整个读完),再多就只显示首尾各三项并
    // 写明总数。

    /** 一个列表或一张表超过多少项就缩略成首尾几项。 */
    public static final int DISPLAY_THRESHOLD = 20;

    /** 缩略时首尾各显示几项。 */
    public static final int DISPLAY_EDGE_ITEMS = 3;

    /**
     * 表里套表最多显示几层,更深的写成 {@code {...}}。五层够一个"团的列表":列表、团、方块的列表、方块、方块的位置。
     */
    public static final int DISPLAY_DEPTH = 5;

    /** 表里一段文字最多显示多少字,超出的截掉开头以后的部分并写明总长。 */
    public static final int DISPLAY_STRING_CHARS = 200;
}
