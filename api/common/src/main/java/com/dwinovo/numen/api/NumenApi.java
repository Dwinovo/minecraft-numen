package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.NumenTool;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 插件手里的那个对象——<b>扩展 Numen 的唯一一扇门</b>。
 *
 * <pre>{@code
 * NumenPlugins.register(numen -> {
 *     numen.registerTool(new MyTool());
 *     numen.bundleSkills(myJarSkillsRoot());
 *     numen.on(CompanionEvent.SPAWN, body -> ...);
 * });
 * }</pre>
 *
 * <h2>为什么收成一扇门</h2>
 * 能力散在 {@code ToolRegistry} / {@code SkillRegistry} / 生命周期监听各处时,第三方得先猜今天这件事属于哪一派、类在哪个包、是静态方法还是单例。
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
     * 注册一个大模型可以调用的工具。它和内置工具一视同仁——同样进目录、同样被
     * 渐进披露、同样按名字调用。
     */
    void registerTool(NumenTool tool);

    /**
     * 把一个目录里的技能交给引擎。就地读,不复制:你的 jar 一卸载技能跟着消失。
     * 玩家在 {@code config/numen/skills/} 放同名目录可以覆盖你这份。
     *
     * <p>通常传你自己 jar 里的 {@code skills/}。专用服务器上是空操作。
     */
    void bundleSkills(Path skillsRoot);

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
     * 把一句话交给同伴的内置大脑,效果和主人亲手打字一样。
     *
     * <p>这是<b>进</b>的方向。出的方向不在这里:同伴要说什么、要做什么,是它自己
     * 调用工具的结果——注册一个工具,它有话说的时候会调你。
     */
    Delivery enqueue(UUID companion, String message);

}
