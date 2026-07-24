package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.platform.services.INetworkChannel;
import com.dwinovo.numen.platform.services.IPlatformHelper;
import com.dwinovo.numen.platform.services.IBlockCapabilityReader;

import java.util.ServiceLoader;

public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkChannel NETWORK = load(INetworkChannel.class);
    public static final INumenConfig CONFIG = load(INumenConfig.class);
    public static final IBlockCapabilityReader CAPS = load(IBlockCapabilityReader.class);
    // 语音工厂是客户端专属服务,注册在 client 包的 ClientServices 里——
    // 其实现类引用 client-only 的 Sound 类,放这里急加载会在专用服务端
    // 掀翻 mod 构造(dist 边界由类结构表达,共享注册表只放两侧通用的服务)。

    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
