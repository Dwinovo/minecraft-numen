package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gear.GearSource;
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
 * <p>脚本能做的就是门能做的:函数组、同伴事件、事件类型与发事件、身体状态片段、穿戴来源。动世界的事只能经函数里的
 * {@link ScriptCall} 受权的几个动作,身体做了什么照常报告给模型。
 */
public final class ScriptPlugin {

    private static final Map<String, CompanionEvent<?>> EVENTS = Map.of(
            "spawn", CompanionEvent.SPAWN,
            "remove", CompanionEvent.REMOVE,
            "death", CompanionEvent.DEATH,
            "abort", CompanionEvent.ABORT,
            "hurt", CompanionEvent.HURT);

    private final String namespace;
    private final List<Consumer<NumenApi>> registrations = new ArrayList<>();
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
     * 订阅同伴身上的事:{@code event} 是 spawn、remove、death、abort、hurt 之一。
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
