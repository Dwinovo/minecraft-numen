package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.IPlatformHelper;
import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.Registries;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.registries.DeferredRegister;

public class NeoForgePlatformHelper implements IPlatformHelper {

    /** 引擎登记的指令参数类型;模组构造时挂上模组总线({@code NumenMod}),注册事件到来时写进注册表。 */
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENT_TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, Constants.MOD_ID);

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> void registerArgumentType(
            String name, Class<A> type, ArgumentTypeInfo<A, T> info) {
        ARGUMENT_TYPES.register(name, () -> ArgumentTypeInfos.registerByClass(type, info));
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }
}
