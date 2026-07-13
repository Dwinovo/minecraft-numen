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

    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
