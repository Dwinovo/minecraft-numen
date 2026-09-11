package com.dwinovo.numen.plugins;

import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 本加载器内嵌了哪些联动、各自要谁——以及加载器替它们做的那几件事。
 *
 * <p>清单在这里,不做扫描:内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放
 * 集合用的。列在一处,"现在内嵌了哪些、各自要谁"一眼答得完。闸门本身三个加载器共用,
 * 见 {@link Gate}。
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
        Gate gate = new Gate(ModList.get()::isLoaded);
        gate.open("yes_steve_model", "ysm", skills -> () -> YsmOnNeoForge.install(skills));
        gate.open("touhou_little_maid", "tlm",
                skills -> () -> com.dwinovo.numen.plugins.tlm.NumenTlm.install(modBus, skills));
    }

    /** YSM 联动只写原版;它要的加载器专属的三件事,NeoForge 的答案在这里。 */
    private static final class YsmOnNeoForge implements YsmHost {
        static void install(Path skills) {
            com.dwinovo.numen.plugins.ysm.NumenYsm.install(new YsmOnNeoForge(), skills);
        }

        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public void onServerTick(Consumer<MinecraftServer> listener) {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> listener.accept(e.getServer()));
        }

        @Override
        public Ysm.Storage storage() {
            return Ysm.Storage.NEOFORGE;
        }
    }
}
