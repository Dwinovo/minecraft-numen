package com.dwinovo.numen.plugins.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity constants for the Minecraft plugin, the vanilla content that ships inside the engine jar. The engine
 * has its own {@code com.dwinovo.numen.api.Constants}; this is deliberately a separate type in a separate package so
 * the two mods never put the same fully-qualified class on the runtime classpath.
 */
public final class Constants {

    /** The plugin's mod id; also its namespace for resources and translation keys (the Lua namespace is {@link #PLUGIN}). */
    public static final String MOD_ID = "numen_minecraft";
    /** The plugin's id and Lua namespace: {@code minecraft.move.walk}. */
    public static final String PLUGIN = "minecraft";
    public static final String MOD_NAME = "Numen Minecraft";
    public static final Logger LOG = LoggerFactory.getLogger("NumenContent");

    private Constants() {}
}
