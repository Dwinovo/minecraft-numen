package com.dwinovo.numen.platform;

import com.dwinovo.numen.api.platform.Services;

/** Numen(内容)问平台要的服务,取法与 Numen API 的 {@link Services} 相同。 */
public final class NumenServices {

    public static final IVanillaAccess VANILLA = Services.load(IVanillaAccess.class, NumenServices.class.getClassLoader());

    private NumenServices() {}
}
