package com.dwinovo.numen;

import com.dwinovo.numen.api.PluginTags;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/** Numen 的引导阶段:自带的标签({@code numen:tame_foods} 等)只能在这时登记,见 {@link PluginTags}。 */
public final class NumenBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        PluginTags.register(context);
    }
}
