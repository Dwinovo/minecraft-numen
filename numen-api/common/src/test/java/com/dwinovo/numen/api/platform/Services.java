package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPermissions;

/**
 * 单测里的服务表:单测没有加载器,真正的 {@code Services} 一初始化就要把每个平台服务都找出来。这里只放指令树用到的那一个,
 * 取模组的实现(判据与模组上一致)。同名同包,测试类路径上排在真的前面。
 */
public final class Services {

    public static final IPermissions PERMISSIONS = new ModPermissions();

    private Services() {}
}
