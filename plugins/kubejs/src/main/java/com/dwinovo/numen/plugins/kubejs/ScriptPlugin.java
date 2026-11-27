package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.api.sdk.ApiFunction;
import dev.latvian.mods.rhino.util.HideFromJS;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 脚本里声明的一个插件:往门里登记什么,只是一串记录。登记({@link #setup})把它们按序交给{@code NumenPlugins.register}拿到的那扇门——
 * 和 Java 插件的 setup 一样;撤回之后重登(管理员关了再开)再交一遍,所以声明时就读好的东西(函数的描述)不依赖那扇门。
 *
 * <p>脚本能做的就是门能做的:函数组、插件的说明、同伴事件、事件类型与发事件、身体状态片段、穿戴来源、知识来源(物品说明与配方)。动世界的事只能经函数里的
 * {@link ScriptCall} 受权的几个动作,身体做了什么照常报告给模型。
 */
public final class ScriptPlugin {

    private static final Map<String, CompanionEvent<?>> EVENTS = Map.of(
            "spawn", CompanionEvent.SPAWN,
            "remove", CompanionEvent.REMOVE,
            "death", CompanionEvent.DEATH,
            "abort", CompanionEvent.ABORT,
            "hurt", CompanionEvent.HURT,
            "run_started", CompanionEvent.RUN_STARTED,
            "run_ended", CompanionEvent.RUN_ENDED,
            "task_finished", CompanionEvent.TASK_FINISHED);

    private final String namespace;
    private final List<Consumer<NumenApi>> registrations = new ArrayList<>();
    /** 脚本里声明的查询函数(recipes、uses、info),装进这个插件的 {@code lookup} 组。 */
    private final List<ApiFunction> lookups = new ArrayList<>();
    /** 这一次登记拿到的门;发事件用它(撤回之后它过期,发事件就抛)。 */
    private volatile NumenApi door;
    private Throwable failure;

    ScriptPlugin(String namespace) {
        this.namespace = namespace;
    }

    /**
     * 一组函数:{@code declare} 里一个个 {@code group.fn(名字, …)}。说明必填(模型读的就是它)。
     *
     * @throws IllegalArgumentException 一个函数都没有、函数的描述不全
     */
    public void group(String name, String summary, Consumer<ScriptGroup> declare) {
        ScriptGroup group = new ScriptGroup(namespace, name);
        declare.accept(group);
        var functions = group.functions();
        registrations.add(numen -> numen.api(name, summary, functions));
    }

    /**
     * 这个插件的说明(Markdown):它是干什么的、几组函数怎么配合、一两个例子。她的上下文里只有名字空间,要用时经 {@code ApiSearch} 读到它。
     * 不强制写。
     */
    public void guide(String markdown) {
        registrations.add(numen -> numen.guide(markdown));
    }

    /**
     * 一个物品查询:{@code kind} 是 {@code recipes}、{@code uses}、{@code info}(物品说明);{@code declare} 里写它({@link ScriptSource})。
     * 模型读到的函数是这个插件自己的 {@code <名字空间>.lookup.<kind>}。
     *
     * @throws IllegalArgumentException 没有这种查询、同一种写了两次、说明或执行体没写
     */
    public void knowledgeSource(String kind, Consumer<ScriptSource> declare) {
        if (!ScriptSource.KINDS.contains(kind)) {
            throw new IllegalArgumentException("no lookup '" + kind + "'; the kinds are recipes, uses and info");
        }
        if (lookups.stream().anyMatch(f -> f.name().equals(kind))) {
            throw new IllegalArgumentException("the lookup '" + kind + "' of plugin " + namespace + " is declared twice");
        }
        ScriptSource source = new ScriptSource(namespace, kind);
        declare.accept(source);
        lookups.add(source.build());
    }

    /**
     * 订阅同伴身上的事:{@code event} 是 spawn、remove、death、abort、hurt、run_started、run_ended、task_finished 之一。
     *
     * @throws IllegalArgumentException 没有这个事件
     */
    @SuppressWarnings("unchecked")
    public void on(String event, Consumer<Object> handler) {
        CompanionEvent<Object> known = (CompanionEvent<Object>) EVENTS.get(event);
        if (known == null) {
            throw new IllegalArgumentException("no companion event '" + event + "'; the events are "
                    + String.join(", ", EVENTS.keySet().stream().sorted().toList()));
        }
        registrations.add(numen -> numen.on(known, handler));
    }

    /** 登记一种事件。{@code alwaysUrgent}:发的时候怎么标都是急件(她不知道就会做错事的那种)。 */
    public void eventType(String type, boolean alwaysUrgent) {
        registrations.add(numen -> numen.registerEventType(type, alwaysUrgent));
    }

    /** 往她的收件箱发一条自己登记过的事件。 */
    public void emit(NumenPlayer companion, String type, Map<String, String> attrs, String text, boolean urgent) {
        NumenApi current = door;
        if (current == null) {
            throw new IllegalStateException("plugin '" + namespace + "' is not registered: it cannot emit events");
        }
        current.emit(companion, type, attrs, text, urgent);
    }

    /** 每轮从她身上读一段状态,拼进她看到的身体状态;空串是此刻没有话说。 */
    public void bodyState(Function<NumenPlayer, String> fragment) {
        registrations.add(numen -> numen.contributeBodyState(fragment));
    }

    /**
     * 一种远程武器(枪、魔杖):{@code declare} 里写它怎么认、怎么准备、怎么射、弹道什么样({@link ScriptWeapon})。攻击任务像用弓一样用它,
     * 该不该打由权限层裁决。
     *
     * @throws IllegalArgumentException 描述不全
     */
    public void rangedWeapon(String name, Consumer<ScriptWeapon> declare) {
        ScriptWeapon weapon = new ScriptWeapon(name);
        declare.accept(weapon);
        var provider = weapon.build();
        registrations.add(numen -> numen.rangedWeapon(provider));
    }

    /** 一个穿戴来源:对象带 {@code slots(body)} 与 {@code kindsOf(body, stack)} 两个方法。 */
    public void gear(GearSource source) {
        registrations.add(numen -> numen.registerGear(source));
    }

    @HideFromJS
    void setup(NumenApi numen) {
        door = numen;
        registrations.forEach(registration -> registration.accept(numen));
        if (!lookups.isEmpty()) {
            numen.api("lookup", "What the plugin " + namespace + " knows about items.", List.copyOf(lookups));
        }
    }

    @HideFromJS
    void failed(Throwable cause) {
        failure = cause;
    }

    @HideFromJS
    Throwable failure() {
        return failure;
    }

    @HideFromJS
    String namespace() {
        return namespace;
    }
}
