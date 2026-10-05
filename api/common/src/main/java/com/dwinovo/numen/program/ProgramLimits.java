package com.dwinovo.numen.program;

/**
 * 服务端跑玩家程序的上限,数值只在这里。一段程序自己的上限(调用数、指令数、字符串字节、墙钟)在
 * {@link com.dwinovo.numen.agent.script.ScriptLimits}。
 */
public final class ProgramLimits {

    private ProgramLimits() {}

    /**
     * 一位主人名下同时跑几段程序:每只同伴同一刻至多一段,这里管一位主人养了好几只、每只都在跑(一段程序等着它派的活做完,干一件活的
     * 时候它一直在)。八段:一位主人的同伴不止这个数的时候很少,多出来的是脚本写错了或有人在试探。
     */
    public static final int PER_OWNER = 8;

    /**
     * 整个服务器同时跑几段程序。每段占两条虚拟线程(停在等结果上不占平台线程)和一个 Lua 虚拟机的堆(几兆以内),CPU 由下面两条每刻
     * 预算和沙箱的指令数管着,所以这里管的是内存与线程数:六十四段,够一台开给十来位玩家的服务器各养几只。
     */
    public static final int SERVER_WIDE = 64;

    /** 服务端为一位主人缓存的模块正文最多多少字节(UTF-8):出厂那一套约 35 KB,她自己的模块都很小;4 MB 够几十个版本,满了丢最久没用的。 */
    public static final long MODULE_CACHE_BYTES = 4L << 20;

    /**
     * 每个服务器刻里,所有程序的服务端调用在主线程上最多花多久:10 毫秒,是一刻 50 毫秒的五分之一。照 CC: Tweaked 的
     * {@code max_main_global_time} 取同一个数——它的定法是让玩家的程序占不了一刻里世界自己要用的时间,又够几十个程序每刻各做一两件事。
     * 到了这个数,没轮到的调用留到下一刻,起点每刻往后轮一段程序,不会总是同一段吃不到。
     */
    public static final long TICK_NANOS_ALL = 10_000_000L;

    /**
     * 每个服务器刻里,一段程序的服务端调用在主线程上最多花多久:5 毫秒。照 CC: Tweaked 的 {@code max_main_computer_time}:
     * 一段程序一刻里排着几个调用也占不满主线程,一个慢调用(整片扫描)之后,这段程序这一刻的其余调用让给别人。
     * 预算在调用之间查,一个调用一旦开始就跑完,所以一次调用本身的耗时不在这里管。
     */
    public static final long TICK_NANOS_PER_PROGRAM = 5_000_000L;

    /**
     * 主人的客户端答一次反向请求最多等多少服务器刻:600 刻(三十秒)。客户端函数是就地的只读查询(读文件、读她的循环状态),正常在毫秒到
     * 几百毫秒内答复,三十秒是它的百倍以上,容得下客户端一次长的卡顿、加载与垃圾回收;又远小于一段程序的墙钟上限(二十分钟,
     * {@link com.dwinovo.numen.agent.script.ScriptLimits#WALL_MILLIS}),也小于可能长时间运行的工具的量级(Anthropic 的程序化工具调用约四分钟):
     * 一个答不出来的客户端函数只卡掉这一次调用的三十秒,不卡掉整段程序。按刻数而不按墙钟:服务器卡住的那些刻里客户端的答复也在队里等着,
     * 不该算它迟。
     */
    public static final int CLIENT_ANSWER_TICKS = 30 * 20;
}
