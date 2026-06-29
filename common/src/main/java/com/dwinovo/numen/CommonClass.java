package com.dwinovo.numen;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;

/**
 * Loader-agnostic mod init. Called once from each platform's mod entry point
 * after the loader has finished registry-registration (entity types, payloads,
 * etc.). Everything that depends on the {@code Services} surface or that is
 * pure data-side initialisation lives here.
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("[numen] common init on {} ({})",
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        registerTools();
    }

    /**
     * Populate the global {@link ToolRegistry}. The {@code numen-api} engine is a
     * chat-only companion and registers <em>no</em> tools of its own — the
     * registry starts empty. {@code numen-core} and third-party tool packs add
     * their {@code @NumenAction} holders via
     * {@link com.dwinovo.numen.agent.tool.NumenTools}, in their own init.
     *
     * <p>Kept as an explicit (empty) hook so the engine's init flow and logging
     * read the same whether or not any tools are present.
     */
    public static void registerTools() {
        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }
}
