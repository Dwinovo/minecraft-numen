package com.dwinovo.numen.api;

import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Codec;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 插件手里的那个对象——<b>扩展 Numen 的唯一一扇门</b>。
 *
 * <pre>{@code
 * NumenPlugins.register("mymod", numen -> {
 *     numen.api("machine", "What your mod lets her do.", MachineApi.class);
 *     numen.bundleSkills(myJarSkillsRoot());
 *     numen.on(CompanionEvent.SPAWN, body -> ...);
 * });
 * }</pre>
 *
 * <h2>为什么收成一扇门</h2>
 * 能力散在动作登记处 / {@code SkillRegistry} / 生命周期监听各处时,第三方得先猜今天这件事属于哪一派、类在哪个包、是静态方法还是单例。
 * 收到一处之后,"我要扩展 Numen"只有一个答案。引擎内部照旧用原来那些类,
 * 这里只是它们对外的那一面。
 *
 * <h2>专用服务器上会安静地少几样</h2>
 * 技能和头像喂的是 LLM 与界面,而两者都只活在玩家自己的客户端上。在专用服务器上
 * {@link #bundleSkills} 与 {@link #registerPortrait} 是<b>空操作</b>,不报错。
 * 这样插件不必自己写 {@code if (dist == CLIENT)} ——那种判断写在每个插件里,
 * 就是四个插件四种写法。
 */
public interface NumenApi {

    /**
     * 订阅同伴身上的事。同一个事件可以订阅多次,按注册顺序调用。
     *
     * @param event   见 {@link CompanionEvent} 的常量
     * @param handler 处理器抛异常不会打断其他订阅者,但会被记进日志
     */
    <T> void on(CompanionEvent<T> event, Consumer<T> handler);

    /**
     * 登记一组 API 函数:她的程序里的一张表 {@code <名字空间>.<组>.<函数>(...)}。名字空间由登记者给出——你在
     * {@link NumenPlugins#register(String, NumenPlugin)} 时写的那个 id,引擎自己的是 {@code numen}——这里只写组名。{@code functions}
     * 里每个 {@link com.dwinovo.numen.sdk.Fn} 静态方法是一个函数,签名就是契约(见 {@code Fn}):第一个参数说在哪执行,参数 record 是它的
     * 参数,返回类型说它怎么交回。系统提示里的 API 索引与每个函数的帮助都由这份登记生成。
     *
     * <pre>{@code
     * public final class MachineApi {
     *     public record Start(@Doc("The machine's id.") String id) {}
     *     public record Started(@Doc("Its id.") String id, @Doc("Its state now.") String state) {}
     *
     *     @Fn("Start a machine by its id.")
     *     @Example("mymod.machine.start(\"m1\")")
     *     public static Started start(ServerCall call, Start args) { ... }
     * }
     * numen.api("machine", "What your mod lets her do, in one sentence.", MachineApi.class);
     * }</pre>
     *
     * <p>一个组名在名字空间里只能登记一次,你只能往自己名字空间的组里加函数——引擎自带的组和别的插件的组都够不着。登记在两侧都跑,所以
     * <b>在 {@code NumenPlugins.register} 的块里直接调</b>,别放进 {@link #onClient}。只拦会破坏系统的(见
     * {@link com.dwinovo.numen.sdk.Binder});写法上的问题看 {@link com.dwinovo.numen.sdk.ApiTester#lint} 的报告。
     *
     * @param group     组名,小写英文:领域名词({@code maid}、{@code quest})
     * @param summary   一句话说明,进系统提示里的 API 索引和帮助
     * @param functions 写着这一组函数的类
     * @throws IllegalArgumentException 组名已被占、名字写不出来、有函数绑定不了
     */
    void api(String group, String summary, Class<?> functions);

    /**
     * 给你的一种类型登记它与脚本的值怎么互转,连同它在签名里写成什么。record、枚举、列表与 {@code Map<String, T>} 不用登记。
     *
     * @throws IllegalArgumentException 这个类型已经有了,或它声明的类名被占了
     */
    <T> void codec(Class<T> type, Codec<T> codec);

    /**
     * 把一个目录里的技能交给引擎。就地读,不复制:你的 jar 一卸载技能跟着消失。
     * 玩家在 {@code config/numen/skills/} 放同名目录可以覆盖你这份。
     *
     * <p>通常传你自己 jar 里的 {@code skills/}。专用服务器上是空操作。
     */
    void bundleSkills(Path skillsRoot);

    /**
     * 把一个目录里的 Lua 模块交给引擎:每个 {@code <组名><扩展名>} 是一个随模组发布的模块,用脚本语言写成(扩展名随语言,眼下是
     * {@code .lua}),模块名是你的名字空间加文件名({@code farm.lua} 是 {@code mymod.farm})。模块返回一张函数表
     * ({@code local M = {} … function M.harvest(field) … end … return M}),她的程序以模块名直接用({@code mymod.farm.harvest("wheat")},
     * 第一次用到才装);和你的组同名的模块给那一组加函数,把几个原子函数组合成一件事。系统提示的 API 索引列出每个模块与它的函数,
     * 说明是开头那行注释与每个函数上面的几行注释;{@code numen.module.show(<名字>)} 读全文。
     *
     * <p>登记那一刻只拦名字不合规矩、撞名、读不通;没写说明、装出来不是一张表这类写法问题在 {@code ApiTester.lint} 的报告里。两侧都
     * 登记,所以<b>在 {@code NumenPlugins.register} 的块里直接调</b>,别放进 {@link #onClient}。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通
     */
    void bundleModules(Path modulesRoot);

    /**
     * 跑一段<b>只在客户端才有意义</b>的代码。专用服务器上整块不执行。
     *
     * <p>界面、渲染、头像这类东西只活在玩家的客户端上,而引擎的公共部分刻意不引用
     * 任何客户端类(那条线是有意划的)。所以它们的注册入口在客户端那一侧
     * (如 {@code NumenGateway.registerPortrait}),你在这个块里去调:
     *
     * <pre>{@code
     * numen.onClient(() -> NumenGateway.registerPortrait(new MyPortrait()));
     * }</pre>
     *
     * <p>"现在是不是客户端"这个判断由引擎自己回答——它每个加载器一个写法,
     * 让每个插件各写一遍就是每个插件一种写法。
     */
    void onClient(Runnable clientOnly);

    /**
     * {@code config/numen/} ——引擎和插件共用的配置目录。你的持久数据放这儿,
     * 文件名带上自己的 mod id(如 {@code numen_<mod id>-<名字>.json})。
     *
     * <p>别自己从游戏目录往下拼:客户端、专用服务器、开发环境三种情况下拼法不同,
     * 每个插件各拼一遍就是每个插件一种答案。目录<b>不保证已存在</b>,写之前
     * 自己 {@code createDirectories}。
     */
    Path configDir();

    /**
     * 主人客户端每次发请求时现算一段,挂进这只同伴的 {@code <runtime_state>}。
     *
     * <p>解决的是这么个事:你的工具把同伴改了(换了外观、接了什么设备),她只在
     * <b>调用工具那一轮</b>知道,下一轮、下一次进游戏就忘了。挂在这儿的东西每轮都在,
     * 她随时知道自己现在是什么状态。
     *
     * <p><b>只放只有主人客户端才知道的事</b>——比如客户端渲染的外观。算的时候读得到的只是
     * 客户端手里的数据:她走远了、换了维度,客户端里就没有她的实体。身体上的事实住在服务端:
     * 穿戴位置用 {@link #registerGear},别的用 {@link #contributeBodyState};一个事实只从一边来,
     * 两边都报就是两个会对不上的答案。
     *
     * <pre>{@code
     * numen.contributeState(companion -> wearing(companion) == null ? ""
     *         : "<maid_look>你现在穿着「" + name + "」</maid_look>");
     * }</pre>
     *
     * <p>自己带一个标签;这轮没什么好说的就返回空串。一个字都不入会话历史,
     * 所以随便变——它挂在请求末端,不在字节级稳定的系统提示里,打不碎 prompt 缓存。
     * 抛异常不会打断别的贡献者,但会记进日志。
     */
    void contributeState(Function<UUID, String> fragment);

    /**
     * 服务端:从身体上读一段她此刻的状态,挂进 {@code <runtime_state>},也写进 {@code status self}。
     *
     * <p>给身体上的事实用——模组给她身上加的、背包和穿戴之外的东西。穿戴位置上戴着什么不走这里,
     * 用 {@link #registerGear}:引擎把它渲染成 {@code <worn>},排在所有片段的最前面。它和背包、状态效果同一条路:
     * 引擎在检查身体有没有变化时一并算,和上次不同就随状态包推给主人的客户端,于是她走远了、
     * 换了维度也照样在请求里。只有主人客户端才知道的事(客户端渲染的外观)用 {@link #contributeState};
     * 一个事实只从一边来。
     *
     * <p><b>别放每 tick 都在变的值</b>(剩余秒数、坐标、耐久):变化检测按字符串比,
     * 每 tick 都不一样就每次都推一个包。要精确到那种程度的东西让她调工具去查。
     *
     * <p>自己带一个标签;没什么好说的就返回空串。和 {@link #contributeState} 一样一个字都不入
     * 会话历史。抛异常不会打断别的贡献者,但会记进日志。
     */
    void contributeBodyState(Function<NumenPlayer, String> fragment);

    /**
     * 服务端:登记一处能把东西穿戴在身上的来源——比如一个饰品栏模组的那些槽。
     *
     * <p>登记之后,{@code gear wear} / {@code gear remove} 的穿、脱、自动选位就认得这些位置,每轮挂给模型的 {@code <worn>}
     * 里也会列出它们(空位也列,那是她知道自己有哪些槽名的唯一来源)。原版四件甲也是这样登记的一处,
     * 和你走同一扇门;登记顺序就是自动选位的优先级,原版在最前。穿戴的状态不必再用
     * {@link #contributeBodyState} 报一遍——那就是同一个事实的两个来源。
     *
     * <p>穿戴发生在服务端,所以在 {@code NumenPlugins.register} 的块里直接调,别放进 {@link #onClient}。
     */
    void registerGear(GearSource source);

    /**
     * 登记一种事件——同伴身上会发生、她该知道的一种事(比如饰品插件的 {@code accessory_changed})。
     *
     * <p>登记的是类型表里的一行,和引擎自带的 {@code task_finished}、{@code reflex} 同一种形状:
     * 插话投递、原文交给模型、主人按停止也不清(那是事实)、不进聊天流。你只决定一件事——
     * 这种事是不是<b>恒为急件</b>({@code true} = 她不知道就会做错事,每一条都立刻开一轮;
     * {@code false} = 每次发的时候由你定)。
     *
     * <p>服务端发出口靠它挡住没登记的种类,主人客户端的队列靠它决定怎么投递,所以<b>两侧都要登记</b>:
     * 在 {@code NumenPlugins.register} 的块里直接调,别放进 {@link #onClient}。
     *
     * @throws IllegalArgumentException 这个 id 已经登记过(引擎自带的种类也算)——改不了别人的行
     */
    void registerEventType(String type, boolean alwaysUrgent);

    /**
     * 服务端:她身上发生了一件事,告诉她。
     *
     * <p>和引擎自带的事件走同一个发出口:按类型查表,盖上游戏内时间戳,拼成
     * {@code <event kind="type" …>text</event>};主人在线直接送到他的客户端,离线进出箱,
     * 等他登录时补发。
     *
     * @param type   {@link #registerEventType} 登记过的种类
     * @param attrs  拼进 {@code <event>} 的属性,按迭代顺序;没有就给 null
     * @param text   这件事本身,转义由引擎做
     * @param urgent 她不知道就会做错事 → 立刻开一轮;否则攒着搭车。登记成恒为急件的种类不看它
     * @throws IllegalArgumentException 种类没登记,或者不是世界上发生的事(比如 {@code query})
     */
    void emit(NumenPlayer companion, String type, Map<String, String> attrs, String text, boolean urgent);

    /**
     * 主人的客户端:把一条输入交给同伴的内置大脑,返回实际发生了什么。
     *
     * <p>{@code type} 给 {@code query} 是主人的话,效果和主人亲手打字一样;给一种 {@link #registerEventType}
     * 登记过的世界事件,是客户端这边的来源发来的事(桥接转发别人在群里说的话、直播弹幕),和服务端的事件同形,
     * 急不急看你登记时定的。身体上发生的事住在服务端,从上面那个带身体的 {@code emit} 发。专用服务器上没有
     * 主人的客户端,返回 {@link Delivery#REJECTED}。
     *
     * <p>这是<b>进</b>的方向。出的方向不在这里:同伴要说什么、要做什么,是它自己
     * 调用工具的结果——注册一个工具,它有话说的时候会调你。
     *
     * @throws IllegalArgumentException {@code type} 既不是 {@code query},也不是登记过的世界事件
     */
    Delivery emit(UUID companion, String type, String text);

}
