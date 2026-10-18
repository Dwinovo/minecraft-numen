package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.platform.services.IBoatPhysics;
import com.dwinovo.numen.api.platform.services.INumenConfig;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import com.dwinovo.numen.api.platform.services.ICommandSources;

import java.util.ServiceLoader;

public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkChannel NETWORK = load(INetworkChannel.class);
    public static final INumenConfig CONFIG = load(INumenConfig.class);
    public static final IBlockCapabilityReader CAPS = load(IBlockCapabilityReader.class);
    public static final IBoatPhysics BOATS = load(IBoatPhysics.class);
    public static final ICommandSources COMMAND_SOURCES = load(ICommandSources.class);
    // 语音工厂是客户端专属服务,注册在 client 包的 ClientServices 里——
    // 其实现类引用 client-only 的 Sound 类,放这里急加载会在专用服务端
    // 掀翻 mod 构造(dist 边界由类结构表达,共享注册表只放两侧通用的服务)。

    public static <T> T load(Class<T> clazz) {
        return load(clazz, Services.class.getClassLoader());
    }

    /**
     * 从 {@code loader} 能看到的 {@code META-INF/services} 里取服务。分模块的平台(Paper 的每个插件一个类加载器)上,
     * 服务实现和接口的声明者不在同一个加载器里,要用实现所在的加载器找。
     */
    public static <T> T load(Class<T> clazz, ClassLoader loader) {

        final T loadedService = ServiceLoader.load(clazz, loader)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
