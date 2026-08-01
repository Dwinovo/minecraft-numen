package com.dwinovo.numen.client.platform;

import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.IVoiceSoundFactory;

/**
 * 客户端专属的平台服务注册表。与 {@link Services}(两侧通用)分家的原因:
 * 这里的服务实现类引用 client-only 的 Minecraft 类,专用服务端的 jar 里
 * 没有 client 包,放进共享注册表急加载会在 mod 构造期 NoClassDefFoundError
 * 直接拒绝启动。dist 边界由类结构表达——服务端代码引用本类即是错误信号。
 */
public final class ClientServices {

    /** 语音声音实例的平台工厂(取数机制两侧不同,见 {@link IVoiceSoundFactory})。 */
    public static final IVoiceSoundFactory VOICE = Services.load(IVoiceSoundFactory.class);

    private ClientServices() {}
}
