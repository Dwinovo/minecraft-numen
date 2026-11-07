package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.platform.services.IBoatPhysics;
import com.dwinovo.numen.api.platform.services.IBodyAdmission;
import com.dwinovo.numen.api.platform.services.IBreakingState;
import com.dwinovo.numen.api.platform.services.INumenConfig;
import com.dwinovo.numen.api.platform.services.IPermissions;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import com.dwinovo.numen.api.platform.services.ICommandSources;

import java.util.ServiceLoader;

/**
 * 平台服务表。每个服务各自按需取、取一次:只用到一个服务的代码(和它的测试)不必凑齐全部。
 */
public class Services {

    private static final Lazy<IPlatformHelper> PLATFORM = new Lazy<>(IPlatformHelper.class);
    private static final Lazy<INetworkChannel> NETWORK = new Lazy<>(INetworkChannel.class);
    private static final Lazy<INumenConfig> CONFIG = new Lazy<>(INumenConfig.class);
    private static final Lazy<IBlockCapabilityReader> CAPS = new Lazy<>(IBlockCapabilityReader.class);
    private static final Lazy<IBoatPhysics> BOATS = new Lazy<>(IBoatPhysics.class);
    private static final Lazy<IPermissions> PERMISSIONS = new Lazy<>(IPermissions.class);
    private static final Lazy<ICommandSources> COMMAND_SOURCES = new Lazy<>(ICommandSources.class);
    private static final Lazy<IBodyAdmission> ADMISSION = new Lazy<>(IBodyAdmission.class);
    private static final Lazy<IBreakingState> BREAKING = new Lazy<>(IBreakingState.class);
    // 语音工厂是客户端专属服务,注册在 client 包的 ClientServices 里——
    // 其实现类引用 client-only 的 Sound 类,放这里急加载会在专用服务端
    // 掀翻 mod 构造(dist 边界由类结构表达,共享注册表只放两侧通用的服务)。

    public static IPlatformHelper platform() {
        return PLATFORM.get();
    }

    public static INetworkChannel network() {
        return NETWORK.get();
    }

    public static INumenConfig config() {
        return CONFIG.get();
    }

    public static IBlockCapabilityReader caps() {
        return CAPS.get();
    }

    public static IBoatPhysics boats() {
        return BOATS.get();
    }

    public static IPermissions permissions() {
        return PERMISSIONS.get();
    }

    public static ICommandSources commandSources() {
        return COMMAND_SOURCES.get();
    }

    public static IBodyAdmission admission() {
        return ADMISSION.get();
    }

    public static IBreakingState breaking() {
        return BREAKING.get();
    }

    /** 第一次要用时才去 {@code META-INF/services} 里找的一个服务。 */
    private static final class Lazy<T> {
        private final Class<T> type;
        private T service;

        Lazy(Class<T> type) {
            this.type = type;
        }

        synchronized T get() {
            if (service == null) {
                service = load(type);
            }
            return service;
        }
    }

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
