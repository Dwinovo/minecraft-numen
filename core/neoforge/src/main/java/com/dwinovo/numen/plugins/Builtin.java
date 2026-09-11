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
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll(IEventBus modBus) {   // modBus 留着:下一个联动多半要用
        Gate gate = new Gate(ModList.get()::isLoaded);
        gate.open("yes_steve_model", "ysm",
                skills -> () -> com.dwinovo.numen.plugins.ysm.NumenYsm.install(new YsmOnNeoForge(), skills));
        // 车万女仆不支持这个 MC 版本(它封顶 1.21.1),所以这条分支上没有那个联动模块。
    }

    /** YSM 联动只写原版;它要的加载器专属的三件事,NeoForge 的答案在这里。 */
    private static final class YsmOnNeoForge implements YsmHost {
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
