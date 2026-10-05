package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个 API 函数:脚本里的 {@code <名字空间>.<组>.<函数>(...)}。写在一个静态方法上,方法的签名就是这个函数的契约:
 *
 * <pre>{@code
 * @Fn("Attack one entity until it is dead, lost or out of reach.")
 * @Example("numen.fight.attack(184)")
 * static Job<Fought> attack(ServerCall call, Attack args) { ... }
 * }</pre>
 *
 * <ul>
 *   <li>第一个参数说在哪执行:{@link ServerCall}(服务端:身体、世界)或 {@link ClientCall}(主人客户端:只有那里才有的数据)。
 *       客户端函数是只读查询、不产生副作用(超时或失败后重试是安全的),它的答复不可信(权限层不读它),一次收一批键、在客户端过滤只回
 *       小结果;只是通知主人的事走单向事件,不走它。约定的全文在 {@link ClientCall}。</li>
 *   <li>第二个参数是一个 record,它的组件就是这个函数的参数(见 {@link Binder}):名字从驼峰换成下划线;不是 {@code Optional} 的按顺序
 *       是位置参数,{@code Optional} 的进最后的选项表;说明用 {@link Doc},省略时的意思用 {@link Omitted}。不收参数的函数没有第二个参数。</li>
 *   <li>返回类型说它怎么交回:{@code R} 当场;{@link Pending}{@code <R>} 等主人答复或下一刻;{@link Job}{@code <R>} 占身体进任务槽,
 *       收尾才回到脚本。{@code R} 是 record、Minecraft 的值、列表等任何有值转换的类型({@link LuaCodecs}),不返回值写 {@code void}。</li>
 *   <li>失败只抛 {@link com.dwinovo.numen.agent.script.ApiError};查不到不是失败,返回空表或 {@code Optional.empty()}。</li>
 * </ul>
 * 函数名默认是方法名(驼峰换成下划线);和 Java 的关键字撞了时用 {@link #name} 写出来。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Fn {

    /** 一句话说明:索引、帮助、签名后面都是它。 */
    String value();

    /** 脚本里的函数名;不写是方法名换成下划线。 */
    String name() default "";
}
