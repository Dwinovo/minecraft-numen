package com.dwinovo.numen.api.seat;

/**
 * 座位上说的话:方法名与通知名,两端只认这里。座位是主人客户端上的大脑与服务端上的身体之间的唯一通道,说 JSON-RPC,
 * 装在一种 MC 自定义包里({@code SeatPayload})。每条消息的 {@code params} 里都带 {@code companion}(她的 UUID):
 * 一条连接上可以同时坐着她的主人创建的几只同伴的座位。
 */
public final class SeatMethods {

    private SeatMethods() {}

    // ---- 客户端 → 服务端(请求) ----

    /** 坐上她的座位:{@code companion}、{@code last_seq};答复是 {@link Opened}。只有她的创建者的连接坐得上。 */
    public static final String SEAT_OPEN = "seat/open";
    /** 她能做什么(目录),按注册表现取:{@code companion};答复见 {@code SeatCatalog}。 */
    public static final String CATALOG_GET = "catalog/get";
    /** 跑一段程序:{@code companion}、{@code generation}、{@code program}、{@code code};答复是 {@link ProgramResult}。 */
    public static final String PROGRAM_RUN = "program/run";
    /** 叫程序停在调用之间:{@code companion}、{@code program}、{@code why}。 */
    public static final String PROGRAM_INTERRUPT = "program/interrupt";
    /** 当场切断程序:{@code companion}、{@code program}、{@code stop_body}。 */
    public static final String PROGRAM_CUT = "program/cut";
    /** 叫停身体上在跑的活:{@code companion}。 */
    public static final String BODY_STOP = "body/stop";
    /** 她此刻的样子({@code <runtime_state>})与游戏刻:{@code companion}。 */
    public static final String STATE_GET = "state/get";
    /** 她要说的话,由服务端在聊天里说出来:{@code companion}、{@code text}。 */
    public static final String SAY = "say";
    /** 收到第几号了:{@code companion}、{@code seq};之前的事件服务端不再留。 */
    public static final String EVENTS_ACK = "events/ack";

    // ---- 服务端 → 客户端 ----

    /** 通知:世界上的一件事,带序号、代数与她此刻的样子({@link SeatEvent})。 */
    public static final String EVENTS_NEW = "events/new";
    /** 通知:目录变了(插件、{@code /reload}),再取一次:{@code companion}、{@code catalog}(新的目录版本)。 */
    public static final String CATALOG_CHANGED = "catalog/changed";
    /** 请求:程序里只有主人客户端答得了的函数:{@code companion}、{@code function}、{@code args};答复是那次调用的 {@code ApiReply}。 */
    public static final String CLIENT_CALL = "client/call";
    /** 请求:程序用到的她的模块,服务端按内容哈希缓存,缺哪份要哪份。她的模块在服务端时不用。 */
    public static final String MODULES_GET = "modules/get";
}
