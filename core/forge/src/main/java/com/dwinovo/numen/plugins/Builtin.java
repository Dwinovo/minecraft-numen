package com.dwinovo.numen.plugins;

import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

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

    public static void registerAll(IEventBus modBus) {
        Gate gate = new Gate(ModList.get()::isLoaded);
        gate.open("yes_steve_model", "ysm",
                skills -> () -> com.dwinovo.numen.plugins.ysm.NumenYsm.install(new YsmOnForge(), skills));
        gate.open("touhou_little_maid", "tlm",
                skills -> () -> com.dwinovo.numen.plugins.tlm.NumenTlm.install(modBus, skills));
        // 注:这个 MC 版本上车万女仆没有按坐标播语音的口,所以那个联动只做模型不做语音。
    }

    /** YSM 联动只写原版;它要的加载器专属的三件事,Forge 的答案在这里。 */
    private static final class YsmOnForge implements YsmHost {
        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public void onServerTick(Consumer<MinecraftServer> listener) {
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
                // Forge 的 tick 事件一 tick 发两次(START/END),不判 phase 会跑两遍
                if (e.phase == TickEvent.Phase.END) listener.accept(e.getServer());
            });
        }

        @Override
        public Ysm.Storage storage() {
            return Ysm.Storage.FORGE;
        }
    }
}
