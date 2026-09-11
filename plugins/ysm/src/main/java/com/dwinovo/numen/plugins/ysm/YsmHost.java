package com.dwinovo.numen.plugins.ysm;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 宿主加载器替本联动做的那几件事。
 *
 * <p>本联动只写原版 MC 与 numen api——编译期对着的就只有原版(见 build.gradle),
 * 加载器的类在这里物理上引用不到。加载器之间不一样的东西全收在这一个接口里,
 * 由 core 各加载器模块的 {@code Builtin} 实现,装上时传进来。于是同一份联动代码在
 * Fabric / Forge / NeoForge 上跑,不必按加载器各写一份。
 */
public interface YsmHost {

    /** 加载器的 {@code config/} 根;YSM 的模型目录 {@code config/yes_steve_model/} 在它底下。 */
    Path configDir();

    /** 每个服务端 tick 结束时回调。 */
    void onServerTick(Consumer<MinecraftServer> listener);

    /** YSM 在本加载器上把玩家数据放在 NBT 的哪里。 */
    Ysm.Storage storage();
}
