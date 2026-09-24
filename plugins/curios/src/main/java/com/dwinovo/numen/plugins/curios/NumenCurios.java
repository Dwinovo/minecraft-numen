package com.dwinovo.numen.plugins.curios;

import com.dwinovo.numen.api.NumenPlugins;

/**
 * Curios 联动:把饰品栏作为一处穿戴来源登记进去,{@code equip_item} 的穿、脱、自动选位和 {@code <worn>}
 * 就认得饰品槽了。不加工具、不加事件——动词是现成的,饰品栏只是多出来的名词。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。装没装 Curios 由 {@code Builtin} 那道闸判断,
 * 判断为真才调 {@link #install}。这一点是必须的:{@code CuriosApi} 的静态方法是空桩,实现由 Curios 的
 * Mixin 注入,Curios 不在场时一调就错。
 */
public final class NumenCurios {

    private NumenCurios() {}

    /** 由 {@code Builtin} 在确认 Curios 在场后调用。穿戴发生在服务端,所以直接登记,不放进 onClient。 */
    public static void install() {
        NumenPlugins.register(numen -> numen.registerGear(new CuriosGear()));
    }
}
