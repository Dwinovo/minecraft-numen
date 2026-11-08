package com.dwinovo.numen.plugins;

import com.dwinovo.numen.ModJar;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

/**
 * 本加载器内嵌了哪些联动、各自要谁——以及加载器替它们做的那几件事。
 *
 * <p>清单在这里,不做扫描:内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放
 * 集合用的。列在一处,"现在内嵌了哪些、各自要谁"一眼答得完。闸门本身三个加载器共用,
 * 见 {@link Gate}。
 *
 * <p>这一代 Forge 上只有 Curios 与 JEI:YSM、车万女仆、森罗厨房、FTB Quests 没有 1.20.2 的构建,Forgematica 也没有 Forge 1.20.2 的版本。
 *
 * <h2>联动的类型只许出现在嵌套类里</h2>
 * 本类自己的方法(含 lambda 编译出来的合成方法)一个都不能提联动的类型:校验器为了核对
 * 参数类型会把它们提前加载,而开发运行(datagen、runClient)里联动不在类路径上——
 * compileOnly——提前加载就是 {@code ClassNotFoundException},整个模组入口跟着炸。
 * 各联动的接线放进各自的嵌套类,闸门开了才碰到它。
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll(IEventBus modBus) {
        Gate gate = new Gate(ModList.get()::isLoaded, ModJar::find);
        gate.open("curios", "curios", () -> com.dwinovo.numen.plugins.curios.NumenCurios::install);
        gate.openClientMod("jei", "jei", () -> JeiOnForge::install);
    }

    /** JEI 联动要加载器答的一件事:流体成分在 Forge 上是 {@code FluidStack},数量直接是毫桶。 */
    private static final class JeiOnForge implements com.dwinovo.numen.plugins.jei.JeiHost {
        static void install() {
            com.dwinovo.numen.plugins.jei.NumenJei.install(new JeiOnForge());
        }

        @Override
        public long milliBuckets(Object fluid) {
            return ((net.minecraftforge.fluids.FluidStack) fluid).getAmount();
        }
    }
}
