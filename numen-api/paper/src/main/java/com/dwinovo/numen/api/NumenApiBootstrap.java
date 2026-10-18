package com.dwinovo.numen.api;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/** Numen API 的引导阶段:标签只能在这时登记(见 {@link PluginTags})。服务端还没有起,这里不碰 {@code Services}。 */
public final class NumenApiBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        PluginTags.register(context);
    }
}
