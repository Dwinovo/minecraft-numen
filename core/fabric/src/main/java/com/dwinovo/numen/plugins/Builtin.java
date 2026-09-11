package com.dwinovo.numen.plugins;

import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 本加载器内嵌了哪些联动、各自要谁——以及加载器替它们做的那几件事。
 *
 * <p>清单在这里,不做扫描:内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放
 * 集合用的。列在一处,"现在内嵌了哪些、各自要谁"一眼答得完。闸门本身三个加载器共用,
 * 见 {@link Gate}。
 *
 * <p>只有 YSM:车万女仆没有 Fabric 版,这里装不上它,联动也就无从谈起。
 *
 * <h2>联动的类型只许出现在嵌套类里</h2>
 * 本类自己的方法(含 lambda 编译出来的合成方法)一个都不能提联动的类型:校验器为了核对
 * 参数类型会把它们提前加载,而开发运行(datagen、runClient)里联动不在类路径上——
 * compileOnly——提前加载就是 {@code ClassNotFoundException},整个模组入口跟着炸。
 * 各联动的接线放进各自的嵌套类,闸门开了才碰到它。
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll() {
        Gate gate = new Gate(FabricLoader.getInstance()::isModLoaded);
        gate.open("yes_steve_model", "ysm", skills -> () -> YsmOnFabric.install(skills));
    }

    /** YSM 联动只写原版;它要的加载器专属的三件事,Fabric 的答案在这里。 */
    private static final class YsmOnFabric implements YsmHost {
        static void install(Path skills) {
            com.dwinovo.numen.plugins.ysm.NumenYsm.install(new YsmOnFabric(), skills);
        }

        @Override
        public Path configDir() {
            return FabricLoader.getInstance().getConfigDir();
        }

        @Override
        public void onServerTick(Consumer<MinecraftServer> listener) {
            ServerTickEvents.END_SERVER_TICK.register(listener::accept);
        }

        @Override
        public Ysm.Storage storage() {
            return Ysm.Storage.FABRIC;
        }
    }
}
